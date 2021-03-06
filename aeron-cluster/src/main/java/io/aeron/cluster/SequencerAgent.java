/*
 * Copyright 2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.*;
import io.aeron.cluster.codecs.*;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.ControlledFragmentHandler;
import org.agrona.DirectBuffer;
import org.agrona.collections.ArrayListUtil;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.*;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

class SequencerAgent implements Agent
{
    private static final int MAX_SEND_ATTEMPTS = 3;
    private static final int TIMER_POLL_LIMIT = 10;
    private static final int FRAGMENT_POLL_LIMIT = 10;

    private long nextSessionId = 1;
    private final long pendingSessionTimeoutMs = TimeUnit.SECONDS.toMillis(5);
    private final Aeron aeron;
    private final AgentInvoker aeronClientInvoker;
    private final EpochClock epochClock;
    private final CachedEpochClock cachedEpochClock = new CachedEpochClock();
    private final TimerService timerService;
    private final ExclusivePublication logPublication;
    private final IngressAdapter ingressAdapter;
    private final BufferClaim bufferClaim = new BufferClaim();
    private final Long2ObjectHashMap<ClusterSession> clusterSessionByIdMap = new Long2ObjectHashMap<>();
    private final ArrayList<ClusterSession> pendingSessions = new ArrayList<>();
    private final MessageHeaderEncoder messageHeaderEncoder = new MessageHeaderEncoder();
    private final SessionHeaderEncoder sessionHeaderEncoder = new SessionHeaderEncoder();
    private final SessionEventEncoder sessionEventEncoder = new SessionEventEncoder();
    private final SessionOpenEventEncoder connectEventEncoder = new SessionOpenEventEncoder();
    private final SessionCloseEventEncoder closeEventEncoder = new SessionCloseEventEncoder();
    private final TimerEventEncoder timerEventEncoder = new TimerEventEncoder();

    // TODO: message counter for log
    // TODO: last message correlation id per session counter
    // TODO: Active session limit
    // TODO: Timeout inactive sessions and clean up closed sessions that fail to log.

    SequencerAgent(final Aeron aeron, final ConsensusModule.Context ctx)
    {
        this.aeron = aeron;
        this.aeronClientInvoker = aeron.conductorAgentInvoker();
        this.epochClock = ctx.epochClock();

        final Subscription ingressSubscription = aeron.addSubscription(ctx.ingressChannel(), ctx.ingressStreamId());
        ingressAdapter = new IngressAdapter(this, ingressSubscription, FRAGMENT_POLL_LIMIT);

        logPublication = aeron.addExclusivePublication(ctx.logChannel(), ctx.logStreamId());

        final Subscription timerSubscription = aeron.addSubscription(ctx.timerChannel(), ctx.timerStreamId());
        timerService = new TimerService(
            TIMER_POLL_LIMIT, FRAGMENT_POLL_LIMIT, this, timerSubscription, cachedEpochClock);
    }

    public int doWork() throws Exception
    {
        int workCount = 0;

        final long nowMs = epochClock.time();
        cachedEpochClock.update(nowMs);

        workCount += aeronClientInvoker.invoke();
        workCount += processPendingSessions(pendingSessions, nowMs);
        workCount += ingressAdapter.poll();
        workCount += timerService.poll(nowMs);

        return workCount;
    }

    public String roleName()
    {
        return "sequencer";
    }

    public void onSessionConnect(final long correlationId, final int responseStreamId, final String responseChannel)
    {
        final Publication publication = aeron.addPublication(responseChannel, responseStreamId);
        final long sessionId = nextSessionId++;
        final ClusterSession session = new ClusterSession(sessionId, publication);
        session.lastActivity(cachedEpochClock.time(), correlationId);

        pendingSessions.add(session);
    }

    public void onSessionClose(final long clusterSessionId)
    {
        final ClusterSession session = clusterSessionByIdMap.get(clusterSessionId);
        if (null != session)
        {
            session.close();
            if (appendClosedSessionToLog(session, CloseReason.USER_ACTION))
            {
                clusterSessionByIdMap.remove(clusterSessionId);
            }
        }
    }

    public ControlledFragmentAssembler.Action onSessionMessage(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final long clusterSessionId,
        final long correlationId)
    {
        final ClusterSession session = clusterSessionByIdMap.get(clusterSessionId);
        if (null == session)
        {
            return ControlledFragmentHandler.Action.CONTINUE;
        }
        else if (session.state() == ClusterSession.State.CONNECTED && !appendConnectedSessionToLog(session))
        {
            return ControlledFragmentHandler.Action.ABORT;
        }

        final long nowMs = cachedEpochClock.time();
        sessionHeaderEncoder
            .wrap((UnsafeBuffer)buffer, offset + MessageHeaderEncoder.ENCODED_LENGTH)
            .timestamp(nowMs);

        int attempts = MAX_SEND_ATTEMPTS;
        do
        {
            if (logPublication.offer(buffer, offset, length) > 0)
            {
                session.lastActivity(nowMs, correlationId);

                return ControlledFragmentHandler.Action.CONTINUE;
            }
        }
        while (--attempts > 0);

        return ControlledFragmentHandler.Action.ABORT;
    }

    public void onKeepAlive(final long correlationId, final long clusterSessionId)
    {
        final ClusterSession session = clusterSessionByIdMap.get(clusterSessionId);
        if (null != session)
        {
            session.lastActivity(cachedEpochClock.time(), correlationId);
        }
    }

    private int processPendingSessions(final ArrayList<ClusterSession> pendingSessions, final long nowMs)
    {
        int workCount = 0;

        for (int lastIndex = pendingSessions.size() - 1, i = lastIndex; i >= 0; i--)
        {
            final ClusterSession session = pendingSessions.get(i);

            if (session.state() == ClusterSession.State.INIT && notifySessionOpened(session))
            {
                ArrayListUtil.fastUnorderedRemove(pendingSessions, i, lastIndex);
                lastIndex--;

                appendConnectedSessionToLog(session);

                workCount += 1;
            }
            else if (nowMs > (session.timeOfLastActivityMs() + pendingSessionTimeoutMs))
            {
                ArrayListUtil.fastUnorderedRemove(pendingSessions, i, lastIndex);
                lastIndex--;

                session.close();
            }
        }

        return workCount;
    }

    private boolean appendClosedSessionToLog(final ClusterSession session, final CloseReason closeReason)
    {
        final int length = MessageHeaderEncoder.ENCODED_LENGTH + SessionCloseEventEncoder.BLOCK_LENGTH;

        int attempts = MAX_SEND_ATTEMPTS;
        do
        {
            if (logPublication.tryClaim(length, bufferClaim) > 0)
            {
                closeEventEncoder
                    .wrapAndApplyHeader(bufferClaim.buffer(), bufferClaim.offset(), messageHeaderEncoder)
                    .clusterSessionId(session.id())
                    .timestamp(cachedEpochClock.time())
                    .closeReason(closeReason);

                bufferClaim.commit();

                return true;
            }
        }
        while (--attempts > 0);

        return false;
    }

    private boolean appendConnectedSessionToLog(final ClusterSession session)
    {
        final String channel = session.responsePublication().channel();
        final int length = MessageHeaderEncoder.ENCODED_LENGTH +
            SessionOpenEventEncoder.BLOCK_LENGTH +
            SessionOpenEventEncoder.responseChannelHeaderLength() +
            channel.length();

        int attempts = MAX_SEND_ATTEMPTS;
        do
        {
            if (logPublication.tryClaim(length, bufferClaim) > 0)
            {
                connectEventEncoder
                    .wrapAndApplyHeader(bufferClaim.buffer(), bufferClaim.offset(), messageHeaderEncoder)
                    .clusterSessionId(session.id())
                    .correlationId(session.lastCorrelationId())
                    .timestamp(cachedEpochClock.time())
                    .responseStreamId(session.responsePublication().streamId())
                    .responseChannel(channel);

                bufferClaim.commit();
                session.state(ClusterSession.State.OPEN);

                return true;
            }
        }
        while (--attempts > 0);

        return false;
    }

    private boolean notifySessionOpened(final ClusterSession session)
    {
        final Publication publication = session.responsePublication();
        final int length = MessageHeaderEncoder.ENCODED_LENGTH +
            SessionEventEncoder.BLOCK_LENGTH +
            SessionEventEncoder.detailHeaderLength();

        int attempts = MAX_SEND_ATTEMPTS;
        do
        {
            if (publication.tryClaim(length, bufferClaim) > 0)
            {
                sessionEventEncoder
                    .wrapAndApplyHeader(bufferClaim.buffer(), bufferClaim.offset(), messageHeaderEncoder)
                    .clusterSessionId(session.id())
                    .correlationId(session.lastCorrelationId())
                    .code(EventCode.OK)
                    .detail("");

                bufferClaim.commit();
                session.timeOfLastActivityMs(cachedEpochClock.time());
                session.state(ClusterSession.State.CONNECTED);
                clusterSessionByIdMap.put(session.id(), session);

                return true;
            }
        }
        while (--attempts > 0);

        return false;
    }

    public void onExpireTimer(final long correlationId, final long nowMs)
    {
        final int length = MessageHeaderEncoder.ENCODED_LENGTH + TimerEventEncoder.BLOCK_LENGTH;

        int attempts = MAX_SEND_ATTEMPTS;
        do
        {
            if (logPublication.tryClaim(length, bufferClaim) > 0)
            {
                timerEventEncoder
                    .wrapAndApplyHeader(bufferClaim.buffer(), bufferClaim.offset(), messageHeaderEncoder)
                    .correlationId(correlationId)
                    .timestamp(nowMs);

                bufferClaim.commit();
                return;
            }
        }
        while (--attempts > 0);

        // TODO: queue unsuccessful sends.
        throw new IllegalStateException("Unable to append to log");
    }
}
