/*
 *  Copyright 2017 Real Logic Ltd.
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
package io.aeron.samples.archive;

import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.Subscription;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.RecordingEventsAdapter;
import io.aeron.archive.client.RecordingEventsListener;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.samples.SampleConfiguration;
import org.agrona.CloseHelper;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.console.ContinueBarrier;

import static io.aeron.logbuffer.FrameDescriptor.FRAME_ALIGNMENT;
import static org.agrona.BufferUtil.allocateDirectAligned;

public class EmbeddedRecordingThroughput implements AutoCloseable, RecordingEventsListener
{
    private static final long NUMBER_OF_MESSAGES = SampleConfiguration.NUMBER_OF_MESSAGES;
    private static final int MESSAGE_LENGTH = SampleConfiguration.MESSAGE_LENGTH;
    private static final int FRAGMENT_COUNT_LIMIT = SampleConfiguration.FRAGMENT_COUNT_LIMIT;
    private static final int STREAM_ID = SampleConfiguration.STREAM_ID;
    private static final String CHANNEL = SampleConfiguration.CHANNEL;

    private final MediaDriver driver;
    private final Archive archive;
    private final Aeron aeron;
    private final AeronArchive aeronArchive;
    private final UnsafeBuffer buffer = new UnsafeBuffer(allocateDirectAligned(MESSAGE_LENGTH, FRAME_ALIGNMENT));
    private final RecordingEventsAdapter recordingEventsAdapter;
    private final Thread recordingEventsThread;
    private final Thread consumerThread;
    private volatile long recordingStartTimeMs;
    private volatile long stopPosition;
    private volatile boolean isRunning = true;

    public static void main(final String[] args) throws Exception
    {
        MediaDriver.loadPropertiesFiles(args);

        try (EmbeddedRecordingThroughput test = new EmbeddedRecordingThroughput())
        {
            test.startRecording();

            final ContinueBarrier barrier = new ContinueBarrier("Execute again?");

            do
            {
                test.streamMessages();
            }
            while (barrier.await());

            test.stop();
        }
    }

    public EmbeddedRecordingThroughput()
    {
        driver = MediaDriver.launch(
            new MediaDriver.Context()
                .threadingMode(ThreadingMode.DEDICATED)
                .useConcurrentCounterManager(true)
                .dirsDeleteOnStart(true));

        archive = Archive.launch(
            new Archive.Context()
                .archiveDir(TestUtil.createTempDir())
                .threadingMode(ArchiveThreadingMode.DEDICATED)
                .countersManager(driver.context().countersManager())
                .errorHandler(driver.context().errorHandler()));

        aeron = Aeron.connect();

        aeronArchive = AeronArchive.connect(
            new AeronArchive.Context()
                .aeron(aeron));

        recordingEventsAdapter = new RecordingEventsAdapter(
            this,
            aeron.addSubscription(
                AeronArchive.Configuration.recordingEventsChannel(),
                AeronArchive.Configuration.recordingEventsStreamId()),
            FRAGMENT_COUNT_LIMIT);

        recordingEventsThread = new Thread(this::runRecordingEventPoller);
        recordingEventsThread.setName("recording-events-poller");
        recordingEventsThread.start();

        consumerThread = new Thread(this::runConsumer);
        consumerThread.setName("consumer-thread");
        consumerThread.start();
    }

    public void close()
    {
        CloseHelper.close(aeronArchive);
        CloseHelper.close(archive);
        CloseHelper.close(driver);

        archive.context().deleteArchiveDirectory();
        driver.context().deleteAeronDirectory();
    }

    public void onStart(
        final long recordingId,
        final long startPosition,
        final int sessionId,
        final int streamId,
        final String channel,
        final String sourceIdentity)
    {
        recordingStartTimeMs = System.currentTimeMillis();
        System.out.println("Recording started for id: " + recordingId);
    }

    public void onProgress(final long recordingId, final long startPosition, final long position)
    {
        if (position == stopPosition)
        {
            final long durationMs = System.currentTimeMillis() - recordingStartTimeMs;
            final long recordingLength = position - startPosition;
            final double dataRate = (recordingLength * 1000.0d / durationMs) / TestUtil.MEGABYTE;
            final double recordingMb = recordingLength / TestUtil.MEGABYTE;
            final long msgRate = (NUMBER_OF_MESSAGES / durationMs) * 1000L;

            System.out.printf("Recorded %.02f MB @ %.02f MB/s - %,d msg/sec%n", recordingMb, dataRate, msgRate);
        }
    }

    public void onStop(final long recordingId, final long startPosition, final long stopPosition)
    {
        // System.out.println("Recording stopped for id: " + recordingId + " @ " + stopPosition);
    }

    private void streamMessages()
    {
        stopPosition = -1L;

        try (ExclusivePublication publication = aeron.addExclusivePublication(CHANNEL, STREAM_ID))
        {
            for (int i = 0; i < NUMBER_OF_MESSAGES; i++)
            {
                buffer.putInt(0, i);
                while (publication.offer(buffer, 0, MESSAGE_LENGTH) < 0)
                {
                    Thread.yield();
                }
            }

            stopPosition = publication.position();
        }
    }

    private void stop() throws InterruptedException
    {
        isRunning = false;
        recordingEventsThread.join();
        consumerThread.join();
    }

    private void runRecordingEventPoller()
    {
        while (isRunning)
        {
            final int fragments = recordingEventsAdapter.poll();
            if (fragments == 0)
            {
                Thread.yield();
            }
        }
    }

    private void runConsumer()
    {
        try (Subscription subscription = aeron.addSubscription(CHANNEL, STREAM_ID))
        {
            while (isRunning)
            {
                final int fragments = subscription.poll(TestUtil.NOOP_FRAGMENT_HANDLER, FRAGMENT_COUNT_LIMIT);
                if (fragments == 0)
                {
                    Thread.yield();
                }
            }
        }
    }

    private void startRecording()
    {
        aeronArchive.startRecording(CHANNEL, STREAM_ID, SourceLocation.LOCAL);
    }
}