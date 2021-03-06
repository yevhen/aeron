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
package io.aeron.cluster;

import io.aeron.Aeron;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.service.ClusteredServiceContainer;
import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.*;
import org.agrona.concurrent.status.AtomicCounter;

import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

public final class ConsensusModule implements AutoCloseable
{
    private final Context ctx;
    private final Aeron aeron;
    private final AgentRunner conductorRunner;

    private ConsensusModule(final Context ctx)
    {
        this.ctx = ctx;
        ctx.conclude();

        ctx.aeronContext()
            .errorHandler(ctx.countedErrorHandler())
            .driverAgentInvoker(ctx.mediaDriverAgentInvoker())
            .useConductorAgentInvoker(true)
            .clientLock(new NoOpLock());

        aeron = Aeron.connect(ctx.aeronContext());

        final SequencerAgent conductor = new SequencerAgent(aeron, ctx);
        conductorRunner = new AgentRunner(ctx.idleStrategy(), ctx.errorHandler(), ctx.errorCounter(), conductor);
    }

    private ConsensusModule start()
    {
        AgentRunner.startOnThread(conductorRunner, ctx.threadFactory());
        return this;
    }

    /**
     * Launch an ClusterNode using a default configuration.
     *
     * @return a new instance of an ClusterNode.
     */
    public static ConsensusModule launch()
    {
        return launch(new Context());
    }

    /**
     * Launch an ClusterNode by providing a configuration context.
     *
     * @param ctx for the configuration parameters.
     * @return  a new instance of an ClusterNode.
     */
    public static ConsensusModule launch(final Context ctx)
    {
        return new ConsensusModule(ctx).start();
    }

    /**
     * Get the {@link ConsensusModule.Context} that is used by this {@link ConsensusModule}.
     *
     * @return the {@link ConsensusModule.Context} that is used by this {@link ConsensusModule}.
     */
    public Context context()
    {
        return ctx;
    }

    public void close()
    {
        CloseHelper.close(conductorRunner);
        CloseHelper.close(aeron);
    }

    public static class Context
    {
        private String ingressChannel = AeronCluster.Configuration.ingressChannel();
        private int ingressStreamId = AeronCluster.Configuration.ingressStreamId();
        private String logChannel = ClusteredServiceContainer.Configuration.logChannel();
        private int logStreamId = ClusteredServiceContainer.Configuration.logStreamId();
        private String timerChannel = ClusteredServiceContainer.Configuration.timerChannel();
        private int timerStreamId = ClusteredServiceContainer.Configuration.timerStreamId();

        private ThreadFactory threadFactory;
        private Supplier<IdleStrategy> idleStrategySupplier;
        private EpochClock epochClock;

        private ErrorHandler errorHandler;
        private AtomicCounter errorCounter;
        private CountedErrorHandler countedErrorHandler;

        private AgentInvoker mediaDriverAgentInvoker;
        private Aeron.Context aeronContext;

        public void conclude()
        {
            if (null == aeronContext)
            {
                aeronContext = new Aeron.Context();
            }

            if (null == threadFactory)
            {
                threadFactory = Thread::new;
            }

            if (null == idleStrategySupplier)
            {
                idleStrategySupplier = ClusteredServiceContainer.Configuration.idleStrategySupplier(null);
            }

            if (null == epochClock)
            {
                epochClock = new SystemEpochClock();
            }

            if (null == errorHandler)
            {
                throw new IllegalStateException("Error handler must be supplied");
            }

            if (null == errorCounter)
            {
                throw new IllegalStateException("Error counter must be supplied");
            }

            if (null == countedErrorHandler)
            {
                countedErrorHandler = new CountedErrorHandler(errorHandler, errorCounter);
            }
        }

        /**
         * Set the channel parameter for the ingress channel.
         *
         * @param channel parameter for the ingress channel.
         * @return this for a fluent API.
         * @see AeronCluster.Configuration#INGRESS_CHANNEL_PROP_NAME
         */
        public Context ingressChannel(final String channel)
        {
            ingressChannel = channel;
            return this;
        }

        /**
         * Get the channel parameter for the ingress channel.
         *
         * @return the channel parameter for the ingress channel.
         * @see AeronCluster.Configuration#INGRESS_CHANNEL_PROP_NAME
         */
        public String ingressChannel()
        {
            return ingressChannel;
        }

        /**
         * Set the stream id for the ingress channel.
         *
         * @param streamId for the ingress channel.
         * @return this for a fluent API
         * @see AeronCluster.Configuration#INGRESS_STREAM_ID_PROP_NAME
         */
        public Context ingressStreamId(final int streamId)
        {
            ingressStreamId = streamId;
            return this;
        }

        /**
         * Get the stream id for the ingress channel.
         *
         * @return the stream id for the ingress channel.
         * @see AeronCluster.Configuration#INGRESS_STREAM_ID_PROP_NAME
         */
        public int ingressStreamId()
        {
            return ingressStreamId;
        }

        /**
         * Set the channel parameter for the cluster log channel.
         *
         * @param channel parameter for the cluster log channel.
         * @return this for a fluent API.
         * @see ClusteredServiceContainer.Configuration#LOG_CHANNEL_PROP_NAME
         */
        public Context logChannel(final String channel)
        {
            logChannel = channel;
            return this;
        }

        /**
         * Get the channel parameter for the cluster log channel.
         *
         * @return the channel parameter for the cluster channel.
         * @see ClusteredServiceContainer.Configuration#LOG_CHANNEL_PROP_NAME
         */
        public String logChannel()
        {
            return logChannel;
        }

        /**
         * Set the stream id for the cluster log channel.
         *
         * @param streamId for the cluster log channel.
         * @return this for a fluent API
         * @see ClusteredServiceContainer.Configuration#LOG_STREAM_ID_PROP_NAME
         */
        public Context logStreamId(final int streamId)
        {
            logStreamId = streamId;
            return this;
        }

        /**
         * Get the stream id for the cluster log channel.
         *
         * @return the stream id for the cluster log channel.
         * @see ClusteredServiceContainer.Configuration#LOG_STREAM_ID_PROP_NAME
         */
        public int logStreamId()
        {
            return logStreamId;
        }

        /**
         * Set the channel parameter for scheduling timer events channel.
         *
         * @param channel parameter for the scheduling timer events channel.
         * @return this for a fluent API.
         * @see ClusteredServiceContainer.Configuration#TIMER_CHANNEL_PROP_NAME
         */
        public Context timerChannel(final String channel)
        {
            timerChannel = channel;
            return this;
        }

        /**
         * Get the channel parameter for the scheduling timer events channel.
         *
         * @return the channel parameter for the scheduling timer events channel.
         * @see ClusteredServiceContainer.Configuration#TIMER_CHANNEL_PROP_NAME
         */
        public String timerChannel()
        {
            return timerChannel;
        }

        /**
         * Set the stream id for the scheduling timer events channel.
         *
         * @param streamId for the scheduling timer events channel.
         * @return this for a fluent API
         * @see ClusteredServiceContainer.Configuration#TIMER_STREAM_ID_PROP_NAME
         */
        public Context timerStreamId(final int streamId)
        {
            timerStreamId = streamId;
            return this;
        }

        /**
         * Get the stream id for the scheduling timer events channel.
         *
         * @return the stream id for the scheduling timer events channel.
         * @see ClusteredServiceContainer.Configuration#TIMER_STREAM_ID_PROP_NAME
         */
        public int timerStreamId()
        {
            return timerStreamId;
        }

        /**
         * Get the thread factory used for creating threads.
         *
         * @return thread factory used for creating threads.
         */
        public ThreadFactory threadFactory()
        {
            return threadFactory;
        }

        /**
         * Set the thread factory used for creating threads.
         *
         * @param threadFactory used for creating threads
         * @return this for a fluent API.
         */
        public Context threadFactory(final ThreadFactory threadFactory)
        {
            this.threadFactory = threadFactory;
            return this;
        }

        /**
         * Provides an {@link IdleStrategy} supplier for the thread responsible for publication/subscription backoff.
         *
         * @param idleStrategySupplier supplier of thread idle strategy for publication/subscription backoff.
         * @return this for a fluent API.
         */
        public Context idleStrategySupplier(final Supplier<IdleStrategy> idleStrategySupplier)
        {
            this.idleStrategySupplier = idleStrategySupplier;
            return this;
        }

        /**
         * Get a new {@link IdleStrategy} based on configured supplier.
         *
         * @return a new {@link IdleStrategy} based on configured supplier.
         */
        public IdleStrategy idleStrategy()
        {
            return idleStrategySupplier.get();
        }

        /**
         * Set the {@link EpochClock} to be used for tracking wall clock time when interacting with the archive.
         *
         * @param clock {@link EpochClock} to be used for tracking wall clock time when interacting with the archive.
         * @return this for a fluent API.
         */
        public Context epochClock(final EpochClock clock)
        {
            this.epochClock = clock;
            return this;
        }

        /**
         * Get the {@link EpochClock} to used for tracking wall clock time within the archive.
         *
         * @return the {@link EpochClock} to used for tracking wall clock time within the archive.
         */
        public EpochClock epochClock()
        {
            return epochClock;
        }

        /**
         * Get the {@link ErrorHandler} to be used by the Archive.
         *
         * @return the {@link ErrorHandler} to be used by the Archive.
         */
        public ErrorHandler errorHandler()
        {
            return errorHandler;
        }

        /**
         * Set the {@link ErrorHandler} to be used by the Archive.
         *
         * @param errorHandler the error handler to be used by the Archive.
         * @return this for a fluent API
         */
        public Context errorHandler(final ErrorHandler errorHandler)
        {
            this.errorHandler = errorHandler;
            return this;
        }

        /**
         * Get the error counter that will record the number of errors the archive has observed.
         *
         * @return the error counter that will record the number of errors the archive has observed.
         */
        public AtomicCounter errorCounter()
        {
            return errorCounter;
        }

        /**
         * Set the error counter that will record the number of errors the cluster node has observed.
         *
         * @param errorCounter the error counter that will record the number of errors the cluster node has observed.
         * @return this for a fluent API.
         */
        public Context errorCounter(final AtomicCounter errorCounter)
        {
            this.errorCounter = errorCounter;
            return this;
        }

        /**
         * Non-default for context.
         *
         * @param countedErrorHandler to override the default.
         * @return this for a fluent API.
         */
        public Context countedErrorHandler(final CountedErrorHandler countedErrorHandler)
        {
            this.countedErrorHandler = countedErrorHandler;
            return this;
        }

        /**
         * The {@link #errorHandler()} that will increment {@link #errorCounter()} by default.
         *
         * @return {@link #errorHandler()} that will increment {@link #errorCounter()} by default.
         */
        public CountedErrorHandler countedErrorHandler()
        {
            return countedErrorHandler;
        }

        /**
         * Get the {@link AgentInvoker} that should be used for the Media Driver if running in a lightweight mode.
         *
         * @return the {@link AgentInvoker} that should be used for the Media Driver if running in a lightweight mode.
         */
        AgentInvoker mediaDriverAgentInvoker()
        {
            return mediaDriverAgentInvoker;
        }

        /**
         * Set the {@link AgentInvoker} that should be used for the Media Driver if running in a lightweight mode.
         *
         * @param mediaDriverAgentInvoker that should be used for the Media Driver if running in a lightweight mode.
         * @return this for a fluent API.
         */
        public Context mediaDriverAgentInvoker(final AgentInvoker mediaDriverAgentInvoker)
        {
            this.mediaDriverAgentInvoker = mediaDriverAgentInvoker;
            return this;
        }

        /**
         * Get the Aeron client context used by the Archive.
         *
         * @return Aeron client context used by the Archive
         */
        public Aeron.Context aeronContext()
        {
            return aeronContext;
        }

        /**
         * Provide an {@link Aeron.Context} for configuring the connection to Aeron.
         * <p>
         * If not provided then a default context will be created.
         *
         * @param aeronContext for configuring the connection to Aeron.
         * @return this for a fluent API.
         */
        public Context aeronContext(final Aeron.Context aeronContext)
        {
            this.aeronContext = aeronContext;
            return this;
        }
    }
}
