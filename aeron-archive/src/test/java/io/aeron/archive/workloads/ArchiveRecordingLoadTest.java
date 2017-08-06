/*
 * Copyright 2014 - 2017 Real Logic Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package io.aeron.archive.workloads;

import io.aeron.*;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.FailRecordingEventsListener;
import io.aeron.archive.TestUtil;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.RecordingEventsAdapter;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FrameDescriptor;
import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.CloseHelper;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.*;
import org.junit.rules.TestWatcher;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static io.aeron.archive.TestUtil.*;
import static io.aeron.logbuffer.LogBufferDescriptor.computeTermIdFromPosition;
import static io.aeron.logbuffer.LogBufferDescriptor.computeTermOffsetFromPosition;
import static org.agrona.BufferUtil.allocateDirectAligned;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

@Ignore
public class ArchiveRecordingLoadTest
{
    private static final String CONTROL_RESPONSE_URI = "aeron:udp?endpoint=localhost:54327";
    private static final int CONTROL_RESPONSE_STREAM_ID = 100;

    private static final String PUBLISH_URI = new ChannelUriStringBuilder()
        .media("ipc")
        .mtu(16 * 1024)
        .termLength(32 * 1024 * 1024)
        .build();

    private static final int TEST_DURATION_SEC = 20;
    private static final int PUBLISH_STREAM_ID = 1;
    private static final int MAX_FRAGMENT_SIZE = 1024;
    private static final double MEGABYTE = 1024.0d * 1024.0d;
    private static final int MESSAGE_COUNT = 2000000;
    private final UnsafeBuffer buffer = new UnsafeBuffer(allocateDirectAligned(4096, FrameDescriptor.FRAME_ALIGNMENT));
    private final Random rnd = new Random();
    private final long seed = System.nanoTime();

    @Rule
    public final TestWatcher testWatcher = TestUtil.newWatcher(this.getClass(), seed);

    private Aeron aeron;
    private Archive archive;
    private MediaDriver driver;
    private AeronArchive aeronArchive;
    private long recordingId;
    private int[] fragmentLength;
    private long totalDataLength;
    private long expectedRecordingLength;
    private long recordedLength;
    private boolean doneRecording;

    private BooleanSupplier recordingStartedIndicator;
    private BooleanSupplier recordingEndIndicator;

    @Before
    public void before() throws Exception
    {
        rnd.setSeed(seed);

        driver = MediaDriver.launch(
            new MediaDriver.Context()
                .threadingMode(ThreadingMode.DEDICATED)
                .useConcurrentCounterManager(true)
                .errorHandler(Throwable::printStackTrace)
                .dirsDeleteOnStart(true));

        archive = Archive.launch(
            new Archive.Context()
                .fileSyncLevel(2)
                .archiveDir(TestUtil.makeTempDir())
                .threadingMode(ArchiveThreadingMode.DEDICATED)
                .countersManager(driver.context().countersManager())
                .errorHandler(driver.context().errorHandler()));

        aeron = Aeron.connect();

        aeronArchive = AeronArchive.connect(
            new AeronArchive.Context()
                .controlResponseChannel(CONTROL_RESPONSE_URI)
                .controlResponseStreamId(CONTROL_RESPONSE_STREAM_ID)
                .aeron(aeron));
    }

    @After
    public void after() throws Exception
    {
        CloseHelper.quietClose(aeronArchive);
        CloseHelper.quietClose(archive);
        CloseHelper.quietClose(driver);

        archive.context().deleteArchiveDirectory();
        driver.context().deleteAeronDirectory();
    }

    @Test
    public void archive() throws IOException, InterruptedException
    {
        try (Subscription recordingEvents = aeron.addSubscription(
                archive.context().recordingEventsChannel(), archive.context().recordingEventsStreamId()))
        {
            initRecordingStartIndicator(recordingEvents);
            initRecordingEndIndicator(recordingEvents);
            awaitConnected(recordingEvents);

            final long duration = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TEST_DURATION_SEC);

            while (System.currentTimeMillis() < duration)
            {
                startDrainingSubscriber(aeron, PUBLISH_URI, PUBLISH_STREAM_ID);
                aeronArchive.startRecording(PUBLISH_URI, PUBLISH_STREAM_ID, SourceLocation.LOCAL);

                long start;
                try (ExclusivePublication publication = aeron.addExclusivePublication(PUBLISH_URI, PUBLISH_STREAM_ID))
                {
                    awaitConnected(publication);
                    await(recordingStartedIndicator);

                    doneRecording = false;
                    start = System.currentTimeMillis();

                    prepAndSendMessages(publication);
                }

                while (!doneRecording)
                {
                    await(recordingEndIndicator);
                }

                printScore(System.currentTimeMillis() - start);
                assertThat(expectedRecordingLength, is(recordedLength));

                aeronArchive.stopRecording(PUBLISH_URI, PUBLISH_STREAM_ID);
            }
        }
    }

    private void printScore(final long time)
    {
        final double rate = (expectedRecordingLength * 1000.0d / time) / MEGABYTE;
        final double recordedMb = expectedRecordingLength / MEGABYTE;
        System.out.printf("%d : sent %.02f MB, recorded @ %.02f MB/s %n", recordingId, recordedMb, rate);
    }

    private void initRecordingStartIndicator(final Subscription recordingEvents)
    {
        final RecordingEventsAdapter recordingEventsAdapter = new RecordingEventsAdapter(
            new FailRecordingEventsListener()
            {
                public void onStart(
                    final long recordingId0,
                    final long startPosition,
                    final int sessionId,
                    final int streamId,
                    final String channel,
                    final String sourceIdentity)
                {
                    recordingId = recordingId0;
                    assertThat(streamId, is(PUBLISH_STREAM_ID));
                }
            },
            recordingEvents,
            1);

        recordingStartedIndicator = () -> recordingEventsAdapter.poll() != 0;
    }

    private void initRecordingEndIndicator(final Subscription recordingEvents)
    {
        final RecordingEventsAdapter recordingEventsAdapter = new RecordingEventsAdapter(
            new FailRecordingEventsListener()
            {
                public void onProgress(
                    final long recordingId0,
                    final long startPosition,
                    final long position)
                {
                    assertThat(recordingId0, is(recordingId));
                    recordedLength = position - startPosition;
                }

                public void onStop(final long recordingId0, final long startPosition, final long stopPosition)
                {
                    doneRecording = true;
                    recordedLength = stopPosition - startPosition;
                    assertThat(recordingId0, is(recordingId));
                }
            },
            recordingEvents,
            1);

        recordingEndIndicator = () -> recordingEventsAdapter.poll() != 0;
    }

    private void prepAndSendMessages(final ExclusivePublication publication)
    {
        fragmentLength = new int[ArchiveRecordingLoadTest.MESSAGE_COUNT];
        for (int i = 0; i < ArchiveRecordingLoadTest.MESSAGE_COUNT; i++)
        {
            final int messageLength = 64 + rnd.nextInt(MAX_FRAGMENT_SIZE - 64) - DataHeaderFlyweight.HEADER_LENGTH;
            fragmentLength[i] = messageLength + DataHeaderFlyweight.HEADER_LENGTH;
            totalDataLength += fragmentLength[i];
        }

        printf("Sending %d messages, total length=%d %n", ArchiveRecordingLoadTest.MESSAGE_COUNT, totalDataLength);

        publishDataToBeRecorded(publication, ArchiveRecordingLoadTest.MESSAGE_COUNT);
    }

    private void publishDataToBeRecorded(final ExclusivePublication publication, final int messageCount)
    {
        buffer.setMemory(0, 1024, (byte)'z');

        final int termLength = publication.termBufferLength();
        final int positionBitsToShift = Integer.numberOfTrailingZeros(termLength);
        final int initialTermId = publication.initialTermId();
        final long startPosition = publication.position();
        final int startTermOffset = computeTermOffsetFromPosition(startPosition, positionBitsToShift);
        final int startTermId = computeTermIdFromPosition(startPosition, positionBitsToShift, initialTermId);

        for (int i = 0; i < messageCount; i++)
        {
            final int dataLength = fragmentLength[i] - DataHeaderFlyweight.HEADER_LENGTH;
            buffer.putInt(0, i);
            offer(publication, buffer, dataLength);
        }

        final long position = publication.position();
        final int lastTermOffset = computeTermOffsetFromPosition(position, positionBitsToShift);
        final int lastTermId = computeTermIdFromPosition(position, positionBitsToShift, initialTermId);
        expectedRecordingLength = ((lastTermId - startTermId) * (long)termLength) + (lastTermOffset - startTermOffset);

        assertThat(position - startPosition, is(expectedRecordingLength));
    }

    private void offer(final ExclusivePublication publication, final UnsafeBuffer buffer, final int length)
    {
        if (publication.offer(buffer, 0, length) < 0)
        {
            final long deadlineNs = System.currentTimeMillis() + TestUtil.TIMEOUT_MS;
            slowOffer(publication, buffer, length, deadlineNs);
        }
    }

    private void slowOffer(
        final ExclusivePublication publication,
        final UnsafeBuffer buffer,
        final int length,
        final long deadlineNs)
    {
        for (int i = 0; i < 3; i++)
        {
            if (publication.offer(buffer, 0, length) > 0)
            {
                return;
            }
        }

        for (int i = 0; i < 100; i++)
        {
            if (publication.offer(buffer, 0, length) > 0)
            {
                return;
            }

            Thread.yield();
        }

        while (publication.offer(buffer, 0, length) < 0)
        {
            LockSupport.parkNanos(TIMEOUT_MS);
            if (System.currentTimeMillis() > deadlineNs)
            {
                fail("Offer has timed out");
            }
        }
    }
}
