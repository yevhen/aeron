/*
 * Copyright 2014-2017 Real Logic Ltd.
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
package io.aeron;

import io.aeron.exceptions.ChannelEndpointException;
import io.aeron.exceptions.ConductorServiceTimeoutException;
import io.aeron.exceptions.DriverTimeoutException;
import io.aeron.exceptions.RegistrationException;
import io.aeron.status.ChannelEndpointStatus;
import io.aeron.status.StaticStatusIndicator;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.ManagedResource;
import org.agrona.collections.ArrayListUtil;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.*;
import org.agrona.concurrent.status.CountersManager;
import org.agrona.concurrent.status.UnsafeBufferPosition;
import org.agrona.concurrent.status.UnsafeBufferStatusIndicator;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import static io.aeron.Aeron.IDLE_SLEEP_NS;
import static io.aeron.Aeron.sleep;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Client conductor receives responses and notifications from Media Driver and acts on them in addition to forwarding
 * commands from the Client API to the Media Driver conductor.
 */
class ClientConductor implements Agent, DriverEventsListener
{
    private static final long NO_CORRELATION_ID = -1;
    private static final long RESOURCE_CHECK_INTERVAL_NS = TimeUnit.SECONDS.toNanos(1);
    private static final long RESOURCE_LINGER_NS = TimeUnit.SECONDS.toNanos(3);

    private final long keepAliveIntervalNs;
    private final long driverTimeoutMs;
    private final long driverTimeoutNs;
    private final long interServiceTimeoutNs;
    private long timeOfLastKeepAliveNs;
    private long timeOfLastResourcesCheckNs;
    private long timeOfLastServiceNs;
    private volatile boolean isClosed;
    private String stashedChannel;
    private RegistrationException driverException;

    private final Lock clientLock;
    private final EpochClock epochClock;
    private final NanoClock nanoClock;
    private final DriverEventsAdapter driverEventsAdapter;
    private final LogBuffersFactory logBuffersFactory;
    private final Long2ObjectHashMap<LogBuffers> logBuffersByIdMap = new Long2ObjectHashMap<>();
    private final Long2ObjectHashMap<Object> resourceByRegIdMap = new Long2ObjectHashMap<>();
    private final ArrayList<ManagedResource> lingeringResources = new ArrayList<>();
    private final UnavailableImageHandler defaultUnavailableImageHandler;
    private final AvailableImageHandler defaultAvailableImageHandler;
    private final UnsafeBuffer counterValuesBuffer;
    private final DriverProxy driverProxy;
    private final ErrorHandler errorHandler;
    private final AgentInvoker driverAgentInvoker;

    ClientConductor(final Aeron.Context ctx)
    {
        clientLock = ctx.clientLock();
        epochClock = ctx.epochClock();
        nanoClock = ctx.nanoClock();
        errorHandler = ctx.errorHandler();
        counterValuesBuffer = ctx.countersValuesBuffer();
        driverProxy = ctx.driverProxy();
        logBuffersFactory = ctx.logBuffersFactory();
        keepAliveIntervalNs = ctx.keepAliveInterval();
        driverTimeoutMs = ctx.driverTimeoutMs();
        driverTimeoutNs = MILLISECONDS.toNanos(driverTimeoutMs);
        interServiceTimeoutNs = ctx.interServiceTimeout();
        defaultAvailableImageHandler = ctx.availableImageHandler();
        defaultUnavailableImageHandler = ctx.unavailableImageHandler();
        driverEventsAdapter = new DriverEventsAdapter(ctx.toClientBuffer(), this);
        driverAgentInvoker = ctx.driverAgentInvoker();

        final long nowNs = nanoClock.nanoTime();
        timeOfLastKeepAliveNs = nowNs;
        timeOfLastResourcesCheckNs = nowNs;
        timeOfLastServiceNs = nowNs;
    }

    public void onClose()
    {
        if (!isClosed)
        {
            isClosed = true;

            final int lingeringResourcesSize = lingeringResources.size();
            forceCloseResources();

            if (lingeringResources.size() > lingeringResourcesSize)
            {
                sleep(1);
            }

            for (int i = 0, size = lingeringResources.size(); i < size; i++)
            {
                lingeringResources.get(i).delete();
            }
        }
    }

    public int doWork()
    {
        int workCount = 0;

        if (clientLock.tryLock())
        {
            try
            {
                if (isClosed)
                {
                    throw new AgentTerminationException();
                }

                workCount = service(NO_CORRELATION_ID);
            }
            finally
            {
                clientLock.unlock();
            }
        }

        return workCount;
    }

    public String roleName()
    {
        return "aeron-client-conductor";
    }

    boolean isClosed()
    {
        return isClosed;
    }

    Lock clientLock()
    {
        return clientLock;
    }

    void handleError(final Throwable ex)
    {
        errorHandler.onError(ex);
    }

    ConcurrentPublication addPublication(final String channel, final int streamId)
    {
        ensureOpen();

        stashedChannel = channel;
        final long registrationId = driverProxy.addPublication(channel, streamId);
        awaitResponse(registrationId);

        return (ConcurrentPublication)resourceByRegIdMap.get(registrationId);
    }

    ExclusivePublication addExclusivePublication(final String channel, final int streamId)
    {
        ensureOpen();

        stashedChannel = channel;
        final long registrationId = driverProxy.addExclusivePublication(channel, streamId);
        awaitResponse(registrationId);

        return (ExclusivePublication)resourceByRegIdMap.get(registrationId);
    }

    void releasePublication(final Publication publication)
    {
        ensureOpen();

        if (publication == resourceByRegIdMap.remove(publication.registrationId()))
        {
            releaseLogBuffers(publication.logBuffers(), publication.originalRegistrationId());
            awaitResponse(driverProxy.removePublication(publication.registrationId()));
        }
    }

    void asyncReleasePublication(final Publication publication)
    {
        releaseLogBuffers(publication.logBuffers(), publication.originalRegistrationId());
        driverProxy.removePublication(publication.registrationId());
    }

    Subscription addSubscription(final String channel, final int streamId)
    {
        return addSubscription(channel, streamId, defaultAvailableImageHandler, defaultUnavailableImageHandler);
    }

    Subscription addSubscription(
        final String channel,
        final int streamId,
        final AvailableImageHandler availableImageHandler,
        final UnavailableImageHandler unavailableImageHandler)
    {
        ensureOpen();

        final long correlationId = driverProxy.addSubscription(channel, streamId);
        final Subscription subscription = new Subscription(
            this,
            channel,
            streamId,
            correlationId,
            availableImageHandler,
            unavailableImageHandler,
            StaticStatusIndicator.TEMP_CHANNEL_STATUS_INDICATOR);

        resourceByRegIdMap.put(correlationId, subscription);

        awaitResponse(correlationId);

        return subscription;
    }

    void releaseSubscription(final Subscription subscription)
    {
        ensureOpen();

        final long registrationId = subscription.registrationId();
        awaitResponse(driverProxy.removeSubscription(registrationId));
        resourceByRegIdMap.remove(registrationId);
    }

    void asyncReleaseSubscription(final Subscription subscription)
    {
        driverProxy.removeSubscription(subscription.registrationId());
    }

    void addDestination(final long registrationId, final String endpointChannel)
    {
        ensureOpen();

        awaitResponse(driverProxy.addDestination(registrationId, endpointChannel));
    }

    void removeDestination(final long registrationId, final String endpointChannel)
    {
        ensureOpen();

        awaitResponse(driverProxy.removeDestination(registrationId, endpointChannel));
    }

    Counter addCounter(
        final int typeId,
        final DirectBuffer keyBuffer,
        final int keyOffset,
        final int keyLength,
        final DirectBuffer labelBuffer,
        final int labelOffset,
        final int labelLength)
    {
        ensureOpen();

        if (keyLength < 0 || keyLength > CountersManager.MAX_KEY_LENGTH)
        {
            throw new IllegalArgumentException("key length out of bounds: " + keyLength);
        }

        if (labelLength < 0 || labelLength > CountersManager.MAX_LABEL_LENGTH)
        {
            throw new IllegalArgumentException("label length out of bounds: " + labelLength);
        }

        final long registrationId = driverProxy.addCounter(
            typeId, keyBuffer, keyOffset, keyLength, labelBuffer, labelOffset, labelLength);

        awaitResponse(registrationId);

        return (Counter)resourceByRegIdMap.get(registrationId);
    }

    void releaseCounter(final Counter counter)
    {
        ensureOpen();

        final long registrationId = counter.registrationId();
        awaitResponse(driverProxy.removeCounter(registrationId));
        resourceByRegIdMap.remove(registrationId);
    }


    void asyncReleaseCounter(final Counter counter)
    {
        driverProxy.removeCounter(counter.registrationId());
    }

    public void onError(final long correlationId, final ErrorCode errorCode, final String message)
    {
        driverException = new RegistrationException(errorCode, message);
    }

    public void onChannelEndpointError(final int statusIndicatorId, final String message)
    {
        for (final Object resource : resourceByRegIdMap.values())
        {
            if (resource instanceof Subscription)
            {
                final Subscription subscription = (Subscription)resource;

                if (subscription.channelStatusIndicator().id() == statusIndicatorId)
                {
                    errorHandler.onError(new ChannelEndpointException(statusIndicatorId, message));
                }
            }
            else if (resource instanceof Publication)
            {
                final Publication publication = (Publication)resource;

                if (publication.channelStatusIndicator().id() == statusIndicatorId)
                {
                    errorHandler.onError(new ChannelEndpointException(statusIndicatorId, message));
                }
            }
        }
    }

    public void onNewPublication(
        final long correlationId,
        final long registrationId,
        final int streamId,
        final int sessionId,
        final int publicationLimitId,
        final int statusIndicatorId,
        final String logFileName)
    {
        resourceByRegIdMap.put(
            correlationId,
            new ConcurrentPublication(
                this,
                stashedChannel,
                streamId,
                sessionId,
                new UnsafeBufferPosition(counterValuesBuffer, publicationLimitId),
                statusIndicatorId != ChannelEndpointStatus.NO_ID_ALLOCATED ?
                    new UnsafeBufferStatusIndicator(counterValuesBuffer, statusIndicatorId) :
                    StaticStatusIndicator.NO_CHANNEL_STATUS_INDICATOR,
                logBuffers(registrationId, logFileName),
                registrationId,
                correlationId));
    }

    public void onNewExclusivePublication(
        final long correlationId,
        final long registrationId,
        final int streamId,
        final int sessionId,
        final int publicationLimitId,
        final int statusIndicatorId,
        final String logFileName)
    {
        resourceByRegIdMap.put(
            correlationId,
            new ExclusivePublication(
                this,
                stashedChannel,
                streamId,
                sessionId,
                new UnsafeBufferPosition(counterValuesBuffer, publicationLimitId),
                statusIndicatorId != ChannelEndpointStatus.NO_ID_ALLOCATED ?
                    new UnsafeBufferStatusIndicator(counterValuesBuffer, statusIndicatorId) :
                    StaticStatusIndicator.NO_CHANNEL_STATUS_INDICATOR,
                logBuffers(registrationId, logFileName),
                registrationId,
                correlationId));
    }

    public void onNewSubscription(final long correlationId, final int statusIndicatorId)
    {
        final Subscription subscription = (Subscription)resourceByRegIdMap.get(correlationId);

        subscription.statusIndicatorReader(
            statusIndicatorId != ChannelEndpointStatus.NO_ID_ALLOCATED ?
                new UnsafeBufferStatusIndicator(counterValuesBuffer, statusIndicatorId) :
                StaticStatusIndicator.NO_CHANNEL_STATUS_INDICATOR);
    }

    public void onAvailableImage(
        final long correlationId,
        final int streamId,
        final int sessionId,
        final long subscriptionRegistrationId,
        final int subscriberPositionId,
        final String logFileName,
        final String sourceIdentity)
    {
        final Subscription subscription = (Subscription)resourceByRegIdMap.get(subscriptionRegistrationId);
        if (null != subscription && !subscription.containsImage(correlationId))
        {
            final Image image = new Image(
                subscription,
                sessionId,
                new UnsafeBufferPosition(counterValuesBuffer, subscriberPositionId),
                logBuffers(correlationId, logFileName),
                errorHandler,
                sourceIdentity,
                correlationId);

            try
            {
                final AvailableImageHandler handler = subscription.availableImageHandler();
                if (null != handler)
                {
                    handler.onAvailableImage(image);
                }
            }
            catch (final Throwable ex)
            {
                errorHandler.onError(ex);
            }

            subscription.addImage(image);
        }
    }

    public void onUnavailableImage(final long correlationId, final long subscriptionRegistrationId, final int streamId)
    {
        final Subscription subscription = (Subscription)resourceByRegIdMap.get(subscriptionRegistrationId);
        if (null != subscription)
        {
            final Image image = subscription.removeImage(correlationId);
            if (null != image)
            {
                try
                {
                    final UnavailableImageHandler handler = subscription.unavailableImageHandler();
                    if (null != handler)
                    {
                        handler.onUnavailableImage(image);
                    }
                }
                catch (final Throwable ex)
                {
                    errorHandler.onError(ex);
                }
            }
        }
    }

    public void onNewCounter(final long correlationId, final int counterId)
    {
        resourceByRegIdMap.put(correlationId, new Counter(correlationId, this, counterValuesBuffer, counterId));
    }

    void releaseImage(final Image image)
    {
        image.close();
        releaseLogBuffers(image.logBuffers(), image.correlationId());
    }

    void releaseLogBuffers(final LogBuffers logBuffers, final long registrationId)
    {
        if (logBuffers.decRef() == 0)
        {
            logBuffers.timeOfLastStateChange(nanoClock.nanoTime());
            logBuffersByIdMap.remove(registrationId);
            lingeringResources.add(logBuffers);
        }
    }

    DriverEventsAdapter driverListenerAdapter()
    {
        return driverEventsAdapter;
    }

    private void ensureOpen()
    {
        if (isClosed)
        {
            throw new IllegalStateException("Aeron client is closed");
        }
    }

    private LogBuffers logBuffers(final long registrationId, final String logFileName)
    {
        LogBuffers logBuffers = logBuffersByIdMap.get(registrationId);
        if (null == logBuffers)
        {
            logBuffers = logBuffersFactory.map(logFileName);
            logBuffersByIdMap.put(registrationId, logBuffers);
        }

        logBuffers.incRef();

        return logBuffers;
    }

    private int service(final long correlationId)
    {
        int workCount = 0;

        try
        {
            workCount += onCheckTimeouts();
            workCount += driverEventsAdapter.receive(correlationId);
        }
        catch (final Throwable throwable)
        {
            errorHandler.onError(throwable);

            if (isClientApiCall(correlationId))
            {
                throw throwable;
            }
        }

        return workCount;
    }

    private static boolean isClientApiCall(final long correlationId)
    {
        return correlationId != NO_CORRELATION_ID;
    }

    private void awaitResponse(final long correlationId)
    {
        driverException = null;
        final long deadlineNs = nanoClock.nanoTime() + driverTimeoutNs;

        do
        {
            if (null == driverAgentInvoker)
            {
                sleep(1);
            }
            else
            {
                driverAgentInvoker.invoke();
            }

            service(correlationId);

            if (driverEventsAdapter.lastReceivedCorrelationId() == correlationId)
            {
                if (null != driverException)
                {
                    throw driverException;
                }

                return;
            }
        }
        while (nanoClock.nanoTime() < deadlineNs);

        throw new DriverTimeoutException("No response from MediaDriver within (ns):" + driverTimeoutNs);
    }

    private int onCheckTimeouts()
    {
        int workCount = 0;
        final long nowNs = nanoClock.nanoTime();

        if (nowNs > (timeOfLastServiceNs + IDLE_SLEEP_NS))
        {
            checkServiceInterval(nowNs);
            timeOfLastServiceNs = nowNs;

            workCount += checkLiveness(nowNs);
            workCount += checkLingeringResources(nowNs);
        }

        return workCount;
    }

    private void checkServiceInterval(final long nowNs)
    {
        if (nowNs > (timeOfLastServiceNs + interServiceTimeoutNs))
        {
            final int lingeringResourcesSize = lingeringResources.size();

            forceCloseResources();

            if (lingeringResources.size() > lingeringResourcesSize)
            {
                sleep(1000);
            }

            onClose();

            throw new ConductorServiceTimeoutException("Exceeded (ns): " + interServiceTimeoutNs);
        }
    }

    private int checkLiveness(final long nowNs)
    {
        if (nowNs > (timeOfLastKeepAliveNs + keepAliveIntervalNs))
        {
            if (epochClock.time() > (driverProxy.timeOfLastDriverKeepaliveMs() + driverTimeoutMs))
            {
                onClose();

                throw new DriverTimeoutException("MediaDriver keepalive older than (ms): " + driverTimeoutMs);
            }

            driverProxy.sendClientKeepalive();
            timeOfLastKeepAliveNs = nowNs;

            return 1;
        }

        return 0;
    }

    private int checkLingeringResources(final long nowNs)
    {
        if (nowNs > (timeOfLastResourcesCheckNs + RESOURCE_CHECK_INTERVAL_NS))
        {
            final ArrayList<ManagedResource> lingeringResources = this.lingeringResources;
            for (int lastIndex = lingeringResources.size() - 1, i = lastIndex; i >= 0; i--)
            {
                final ManagedResource resource = lingeringResources.get(i);
                if (nowNs > (resource.timeOfLastStateChange() + RESOURCE_LINGER_NS))
                {
                    ArrayListUtil.fastUnorderedRemove(lingeringResources, i, lastIndex);
                    lastIndex--;
                    resource.delete();
                }
            }

            timeOfLastResourcesCheckNs = nowNs;

            return 1;
        }

        return 0;
    }

    private void forceCloseResources()
    {
        for (final Object resource : resourceByRegIdMap.values())
        {
            if (resource instanceof Subscription)
            {
                ((Subscription)resource).forceClose();
            }
            else if (resource instanceof Publication)
            {
                ((Publication)resource).forceClose();
            }
            else if (resource instanceof Counter)
            {
                ((Counter)resource).forceClose();
            }
        }

        resourceByRegIdMap.clear();
    }
}
