/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.functions.sink.filesystem;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.MemorySize;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.api.functions.sink.filesystem.TestUtils.Tuple2Encoder;
import org.apache.flink.streaming.api.functions.sink.filesystem.TestUtils.TupleToIntegerBucketer;
import org.apache.flink.streaming.api.functions.sink.filesystem.legacy.StreamingFileSink;
import org.apache.flink.streaming.api.functions.sink.filesystem.rollingpolicies.DefaultRollingPolicy;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.AbstractStreamOperatorTestHarness;
import org.apache.flink.streaming.util.OneInputStreamOperatorTestHarness;
import org.apache.flink.testutils.junit.utils.TempDirUtils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for the {@link StreamingFileSink}. */
class LocalStreamingFileSinkTest {

    @TempDir private static java.nio.file.Path tempFolder;

    @Test
    void testClosingWithoutInput() throws Exception {
        final File outDir = TempDirUtils.newFolder(tempFolder);

        try (OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Object> testHarness =
                TestUtils.createRescalingTestSink(outDir, 1, 0, 100L, 124L); ) {
            testHarness.setup();
            testHarness.open();
        }
    }

    @Test
    void testClosingWithoutInitializingStateShouldNotFail() throws Exception {
        final File outDir = TempDirUtils.newFolder(tempFolder);

        try (OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Object> testHarness =
                TestUtils.createRescalingTestSink(outDir, 1, 0, 100L, 124L)) {
            testHarness.setup();
        }
    }

    @Test
    void testTruncateAfterRecoveryAndOverwrite() throws Exception {
        final File outDir = TempDirUtils.newFolder(tempFolder);
        OperatorSubtaskState snapshot;

        // we set the max bucket size to small so that we can know when it rolls
        try (OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Object> testHarness =
                TestUtils.createRescalingTestSink(outDir, 1, 0, 100L, 10L)) {

            testHarness.setup();
            testHarness.open();

            // this creates a new bucket "test1" and part-0-0
            testHarness.processElement(new StreamRecord<>(Tuple2.of("test1", 1), 1L));
            TestUtils.checkLocalFs(outDir, 1, 0);

            // we take a checkpoint so that we keep the in-progress file offset.
            snapshot = testHarness.snapshot(1L, 1L);

            // these will close part-0-0 and open part-0-1
            testHarness.processElement(new StreamRecord<>(Tuple2.of("test1", 2), 2L));
            testHarness.processElement(new StreamRecord<>(Tuple2.of("test1", 3), 3L));

            TestUtils.checkLocalFs(outDir, 2, 0);

            Map<File, String> contents = TestUtils.getFileContentByPath(outDir);
            int fileCounter = 0;
            for (Map.Entry<File, String> fileContents : contents.entrySet()) {
                if (fileContents.getKey().getName().contains(".part-0-0.inprogress")) {
                    fileCounter++;
                    assertThat(fileContents.getValue()).isEqualTo("test1@1\ntest1@2\n");
                } else if (fileContents.getKey().getName().contains(".part-0-1.inprogress")) {
                    fileCounter++;
                    assertThat(fileContents.getValue()).isEqualTo("test1@3\n");
                }
            }
            assertThat(fileCounter).isEqualTo(2L);
        }

        try (OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Object> testHarness =
                TestUtils.createRescalingTestSink(outDir, 1, 0, 100L, 10L)) {

            testHarness.setup();
            testHarness.initializeState(snapshot);
            testHarness.open();

            // the in-progress is the not cleaned up one and the pending is truncated and finalized
            TestUtils.checkLocalFs(outDir, 2, 0);

            // now we go back to the first checkpoint so it should truncate part-0-0 and restart
            // part-0-1
            int fileCounter = 0;
            for (Map.Entry<File, String> fileContents :
                    TestUtils.getFileContentByPath(outDir).entrySet()) {
                if (fileContents.getKey().getName().contains(".part-0-0.inprogress")) {
                    // truncated
                    fileCounter++;
                    assertThat(fileContents.getValue()).isEqualTo("test1@1\n");
                } else if (fileContents.getKey().getName().contains(".part-0-1.inprogress")) {
                    // ignored for now as we do not clean up. This will be overwritten.
                    fileCounter++;
                    assertThat(fileContents.getValue()).isEqualTo("test1@3\n");
                }
            }
            assertThat(fileCounter).isEqualTo(2L);

            // the first closes part-0-0 and the second will open part-0-1
            testHarness.processElement(new StreamRecord<>(Tuple2.of("test1", 4), 4L));

            fileCounter = 0;
            for (Map.Entry<File, String> fileContents :
                    TestUtils.getFileContentByPath(outDir).entrySet()) {
                if (fileContents.getKey().getName().contains(".part-0-0.inprogress")) {
                    fileCounter++;
                    assertThat(fileContents.getValue()).isEqualTo("test1@1\ntest1@4\n");
                } else if (fileContents.getKey().getName().contains(".part-0-1.inprogress")) {
                    // ignored for now as we do not clean up. This will be overwritten.
                    fileCounter++;
                    assertThat(fileContents.getValue()).isEqualTo("test1@3\n");
                }
            }
            assertThat(fileCounter).isEqualTo(2L);

            testHarness.processElement(new StreamRecord<>(Tuple2.of("test1", 5), 5L));
            TestUtils.checkLocalFs(
                    outDir, 3,
                    0); // the previous part-0-1 in progress is simply ignored (random extension)

            testHarness.snapshot(2L, 2L);

            // this will close the new part-0-1
            testHarness.processElement(new StreamRecord<>(Tuple2.of("test1", 6), 6L));
            TestUtils.checkLocalFs(outDir, 3, 0);

            fileCounter = 0;
            for (Map.Entry<File, String> fileContents :
                    TestUtils.getFileContentByPath(outDir).entrySet()) {
                if (fileContents.getKey().getName().contains(".part-0-0.inprogress")) {
                    fileCounter++;
                    assertThat(fileContents.getValue()).isEqualTo("test1@1\ntest1@4\n");
                } else if (fileContents.getKey().getName().contains(".part-0-1.inprogress")) {
                    if (fileContents.getValue().equals("test1@5\ntest1@6\n")
                            || fileContents.getValue().equals("test1@3\n")) {
                        fileCounter++;
                    }
                }
            }
            assertThat(fileCounter).isEqualTo(3L);

            // this will publish part-0-0
            testHarness.notifyOfCompletedCheckpoint(2L);
            TestUtils.checkLocalFs(outDir, 2, 1);

            fileCounter = 0;
            for (Map.Entry<File, String> fileContents :
                    TestUtils.getFileContentByPath(outDir).entrySet()) {
                if (fileContents.getKey().getName().equals("part-0-0")) {
                    fileCounter++;
                    assertThat(fileContents.getValue()).isEqualTo("test1@1\ntest1@4\n");
                } else if (fileContents.getKey().getName().contains(".part-0-1.inprogress")) {
                    if (fileContents.getValue().equals("test1@5\ntest1@6\n")
                            || fileContents.getValue().equals("test1@3\n")) {
                        fileCounter++;
                    }
                }
            }
            assertThat(fileCounter).isEqualTo(3L);
        }
    }

    @Test
    void testCommitStagedFilesInCorrectOrder() throws Exception {
        final File outDir = TempDirUtils.newFolder(tempFolder);

        // we set the max bucket size to small so that we can know when it rolls
        try (OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Object> testHarness =
                TestUtils.createRescalingTestSink(outDir, 1, 0, 100L, 10L)) {

            testHarness.setup();
            testHarness.open();

            testHarness.setProcessingTime(0L);

            // these 2 create a new bucket "test1", with a .part-0-0.inprogress and also fill it
            testHarness.processElement(new StreamRecord<>(Tuple2.of("test1", 1), 1L));
            testHarness.processElement(new StreamRecord<>(Tuple2.of("test1", 2), 2L));
            TestUtils.checkLocalFs(outDir, 1, 0);

            // this will open .part-0-1.inprogress
            testHarness.processElement(new StreamRecord<>(Tuple2.of("test1", 3), 3L));
            TestUtils.checkLocalFs(outDir, 2, 0);

            // we take a checkpoint so that we keep the in-progress file offset.
            testHarness.snapshot(1L, 1L);

            // this will close .part-0-1.inprogress
            testHarness.processElement(new StreamRecord<>(Tuple2.of("test1", 4), 4L));

            // and open and fill .part-0-2.inprogress
            testHarness.processElement(new StreamRecord<>(Tuple2.of("test1", 5), 5L));
            testHarness.processElement(new StreamRecord<>(Tuple2.of("test1", 6), 6L));
            TestUtils.checkLocalFs(outDir, 3, 0); // nothing committed yet

            testHarness.snapshot(2L, 2L);

            // open .part-0-3.inprogress
            testHarness.processElement(new StreamRecord<>(Tuple2.of("test1", 7), 7L));
            TestUtils.checkLocalFs(outDir, 4, 0);

            // this will close the part file (time)
            testHarness.setProcessingTime(101L);

            testHarness.snapshot(3L, 3L);

            testHarness.notifyOfCompletedCheckpoint(
                    1L); // the pending for checkpoint 1 are committed
            TestUtils.checkLocalFs(outDir, 3, 1);

            int fileCounter = 0;
            for (Map.Entry<File, String> fileContents :
                    TestUtils.getFileContentByPath(outDir).entrySet()) {
                if (fileContents.getKey().getName().equals("part-0-0")) {
                    fileCounter++;
                    assertThat(fileContents.getValue()).isEqualTo("test1@1\ntest1@2\n");
                } else if (fileContents.getKey().getName().contains(".part-0-1.inprogress")) {
                    fileCounter++;
                    assertThat(fileContents.getValue()).isEqualTo("test1@3\ntest1@4\n");
                } else if (fileContents.getKey().getName().contains(".part-0-2.inprogress")) {
                    fileCounter++;
                    assertThat(fileContents.getValue()).isEqualTo("test1@5\ntest1@6\n");
                } else if (fileContents.getKey().getName().contains(".part-0-3.inprogress")) {
                    fileCounter++;
                    assertThat(fileContents.getValue()).isEqualTo("test1@7\n");
                }
            }
            assertThat(fileCounter).isEqualTo(4L);

            testHarness.notifyOfCompletedCheckpoint(
                    3L); // all the pending for checkpoint 2 and 3 are committed
            TestUtils.checkLocalFs(outDir, 0, 4);

            fileCounter = 0;
            for (Map.Entry<File, String> fileContents :
                    TestUtils.getFileContentByPath(outDir).entrySet()) {
                if (fileContents.getKey().getName().equals("part-0-0")) {
                    fileCounter++;
                    assertThat(fileContents.getValue()).isEqualTo("test1@1\ntest1@2\n");
                } else if (fileContents.getKey().getName().equals("part-0-1")) {
                    fileCounter++;
                    assertThat(fileContents.getValue()).isEqualTo("test1@3\ntest1@4\n");
                } else if (fileContents.getKey().getName().equals("part-0-2")) {
                    fileCounter++;
                    assertThat(fileContents.getValue()).isEqualTo("test1@5\ntest1@6\n");
                } else if (fileContents.getKey().getName().equals("part-0-3")) {
                    fileCounter++;
                    assertThat(fileContents.getValue()).isEqualTo("test1@7\n");
                }
            }
            assertThat(fileCounter).isEqualTo(4L);
        }
    }

    @Test
    void testInactivityPeriodWithLateNotify() throws Exception {
        final File outDir = TempDirUtils.newFolder(tempFolder);

        // we set a big bucket size so that it does not close by size, but by timers.
        try (OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Object> testHarness =
                TestUtils.createRescalingTestSink(outDir, 1, 0, 100L, 124L)) {

            testHarness.setup();
            testHarness.open();

            testHarness.setProcessingTime(0L);

            testHarness.processElement(new StreamRecord<>(Tuple2.of("test1", 1), 1L));
            testHarness.processElement(new StreamRecord<>(Tuple2.of("test2", 1), 1L));
            TestUtils.checkLocalFs(outDir, 2, 0);

            int bucketCounter = 0;
            for (Map.Entry<File, String> fileContents :
                    TestUtils.getFileContentByPath(outDir).entrySet()) {
                if (fileContents.getKey().getParentFile().getName().equals("test1")) {
                    bucketCounter++;
                } else if (fileContents.getKey().getParentFile().getName().equals("test2")) {
                    bucketCounter++;
                }
            }
            assertThat(bucketCounter)
                    .isEqualTo(2L); // verifies that we have 2 buckets, "test1" and "test2"

            testHarness.setProcessingTime(101L); // put them in pending
            TestUtils.checkLocalFs(outDir, 2, 0);

            testHarness.snapshot(0L, 0L); // put them in pending for 0
            TestUtils.checkLocalFs(outDir, 2, 0);

            // create another 2 buckets with 1 inprogress file each
            testHarness.processElement(new StreamRecord<>(Tuple2.of("test3", 1), 1L));
            testHarness.processElement(new StreamRecord<>(Tuple2.of("test4", 1), 1L));

            testHarness.setProcessingTime(202L); // put them in pending

            testHarness.snapshot(1L, 0L); // put them in pending for 1
            TestUtils.checkLocalFs(outDir, 4, 0);

            testHarness.notifyOfCompletedCheckpoint(
                    0L); // put the pending for 0 to the "committed" state
            TestUtils.checkLocalFs(outDir, 2, 2);

            bucketCounter = 0;
            for (Map.Entry<File, String> fileContents :
                    TestUtils.getFileContentByPath(outDir).entrySet()) {
                if (fileContents.getKey().getParentFile().getName().equals("test1")) {
                    bucketCounter++;
                    assertThat(fileContents.getKey().getName()).isEqualTo("part-0-0");
                    assertThat(fileContents.getValue()).isEqualTo("test1@1\n");
                } else if (fileContents.getKey().getParentFile().getName().equals("test2")) {
                    bucketCounter++;
                    assertThat(fileContents.getKey().getName()).isEqualTo("part-0-1");
                    assertThat(fileContents.getValue()).isEqualTo("test2@1\n");
                } else if (fileContents.getKey().getParentFile().getName().equals("test3")) {
                    bucketCounter++;
                } else if (fileContents.getKey().getParentFile().getName().equals("test4")) {
                    bucketCounter++;
                }
            }
            assertThat(bucketCounter).isEqualTo(4L);

            testHarness.notifyOfCompletedCheckpoint(
                    1L); // put the pending for 1 to the "committed" state
            TestUtils.checkLocalFs(outDir, 0, 4);

            bucketCounter = 0;
            for (Map.Entry<File, String> fileContents :
                    TestUtils.getFileContentByPath(outDir).entrySet()) {
                if (fileContents.getKey().getParentFile().getName().equals("test1")) {
                    bucketCounter++;
                    assertThat(fileContents.getValue()).isEqualTo("test1@1\n");
                } else if (fileContents.getKey().getParentFile().getName().equals("test2")) {
                    bucketCounter++;
                    assertThat(fileContents.getValue()).isEqualTo("test2@1\n");
                } else if (fileContents.getKey().getParentFile().getName().equals("test3")) {
                    bucketCounter++;
                    assertThat(fileContents.getKey().getName()).isEqualTo("part-0-2");
                    assertThat(fileContents.getValue()).isEqualTo("test3@1\n");
                } else if (fileContents.getKey().getParentFile().getName().equals("test4")) {
                    bucketCounter++;
                    assertThat(fileContents.getKey().getName()).isEqualTo("part-0-3");
                    assertThat(fileContents.getValue()).isEqualTo("test4@1\n");
                }
            }
            assertThat(bucketCounter).isEqualTo(4L);
        }
    }

    @Test
    void testClosingOnSnapshot() throws Exception {
        final File outDir = TempDirUtils.newFolder(tempFolder);

        try (OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Object> testHarness =
                TestUtils.createRescalingTestSink(outDir, 1, 0, 100L, 2L)) {
            testHarness.setup();
            testHarness.open();

            testHarness.setProcessingTime(0L);

            testHarness.processElement(new StreamRecord<>(Tuple2.of("test1", 1), 1L));
            testHarness.processElement(new StreamRecord<>(Tuple2.of("test2", 1), 1L));
            TestUtils.checkLocalFs(outDir, 2, 0);

            // this is to check the inactivity threshold
            testHarness.setProcessingTime(101L);
            TestUtils.checkLocalFs(outDir, 2, 0);

            testHarness.processElement(new StreamRecord<>(Tuple2.of("test3", 1), 1L));
            TestUtils.checkLocalFs(outDir, 3, 0);

            testHarness.snapshot(0L, 1L);
            TestUtils.checkLocalFs(outDir, 3, 0);

            testHarness.notifyOfCompletedCheckpoint(0L);
            TestUtils.checkLocalFs(outDir, 0, 3);

            testHarness.snapshot(1L, 0L);

            testHarness.processElement(new StreamRecord<>(Tuple2.of("test4", 10), 10L));
            TestUtils.checkLocalFs(outDir, 1, 3);
        }

        // at close it is not moved to final.
        TestUtils.checkLocalFs(outDir, 1, 3);
    }

    @Test
    void testClosingWithCustomizedBucketer() throws Exception {
        final File outDir = TempDirUtils.newFolder(tempFolder);
        final long partMaxSize = 2L;
        final long inactivityInterval = 100L;
        final RollingPolicy<Tuple2<String, Integer>, Integer> rollingPolicy =
                DefaultRollingPolicy.builder()
                        .withMaxPartSize(new MemorySize(partMaxSize))
                        .withRolloverInterval(Duration.ofMillis(inactivityInterval))
                        .withInactivityInterval(Duration.ofMillis(inactivityInterval))
                        .build();

        try (OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Object> testHarness =
                TestUtils.createCustomizedRescalingTestSink(
                        outDir,
                        1,
                        0,
                        100L,
                        new TupleToIntegerBucketer(),
                        new Tuple2Encoder(),
                        rollingPolicy,
                        new DefaultBucketFactoryImpl<>()); ) {
            testHarness.setup();
            testHarness.open();

            testHarness.setProcessingTime(0L);

            testHarness.processElement(new StreamRecord<>(Tuple2.of("test1", 1), 1L));
            testHarness.processElement(new StreamRecord<>(Tuple2.of("test2", 2), 1L));
            TestUtils.checkLocalFs(outDir, 2, 0);

            // this is to check the inactivity threshold
            testHarness.setProcessingTime(101L);
            TestUtils.checkLocalFs(outDir, 2, 0);

            testHarness.processElement(new StreamRecord<>(Tuple2.of("test3", 3), 1L));
            TestUtils.checkLocalFs(outDir, 3, 0);

            testHarness.snapshot(0L, 1L);
            TestUtils.checkLocalFs(outDir, 3, 0);

            testHarness.notifyOfCompletedCheckpoint(0L);
            TestUtils.checkLocalFs(outDir, 0, 3);

            testHarness.processElement(new StreamRecord<>(Tuple2.of("test4", 4), 10L));
            TestUtils.checkLocalFs(outDir, 1, 3);

            testHarness.snapshot(1L, 0L);
            testHarness.notifyOfCompletedCheckpoint(1L);
        }

        // at close all files moved to final.
        TestUtils.checkLocalFs(outDir, 0, 4);

        // check file content and bucket ID.
        Map<File, String> contents = TestUtils.getFileContentByPath(outDir);
        for (Map.Entry<File, String> fileContents : contents.entrySet()) {
            Integer bucketId = Integer.parseInt(fileContents.getKey().getParentFile().getName());

            assertThat(bucketId).isBetween(1, 4);
            assertThat(fileContents.getValue())
                    .isEqualTo(String.format("test%d@%d\n", bucketId, bucketId));
        }
    }

    @Test
    void testScalingDownAndMergingOfStates() throws Exception {
        final File outDir = TempDirUtils.newFolder(tempFolder);

        OperatorSubtaskState mergedSnapshot;

        // we set small file size so that the part file rolls on every element.
        try (OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Object> testHarness1 =
                        TestUtils.createRescalingTestSink(outDir, 2, 0, 100L, 10L);
                OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Object> testHarness2 =
                        TestUtils.createRescalingTestSink(outDir, 2, 1, 100L, 10L)) {
            testHarness1.setup();
            testHarness1.open();

            testHarness2.setup();
            testHarness2.open();

            testHarness1.processElement(new StreamRecord<>(Tuple2.of("test1", 0), 0L));
            TestUtils.checkLocalFs(outDir, 1, 0);

            testHarness2.processElement(new StreamRecord<>(Tuple2.of("test1", 1), 1L));
            testHarness2.processElement(new StreamRecord<>(Tuple2.of("test2", 1), 1L));

            // all the files are in-progress
            TestUtils.checkLocalFs(outDir, 3, 0);

            int counter = 0;
            for (Map.Entry<File, String> fileContents :
                    TestUtils.getFileContentByPath(outDir).entrySet()) {
                final String parentFilename = fileContents.getKey().getParentFile().getName();
                final String inProgressFilename = fileContents.getKey().getName();

                if (parentFilename.equals("test1")
                        && (inProgressFilename.contains(".part-0-0.inprogress")
                                || inProgressFilename.contains(".part-1-0.inprogress"))) {
                    counter++;
                } else if (parentFilename.equals("test2")
                        && inProgressFilename.contains(".part-1-1.inprogress")) {
                    counter++;
                }
            }
            assertThat(counter).isEqualTo(3L);

            // intentionally we snapshot them in the reverse order so that the states are shuffled
            mergedSnapshot =
                    AbstractStreamOperatorTestHarness.repackageState(
                            testHarness1.snapshot(1L, 0L), testHarness2.snapshot(1L, 0L));
        }

        final OperatorSubtaskState initState =
                AbstractStreamOperatorTestHarness.repartitionOperatorState(
                        mergedSnapshot, TestUtils.MAX_PARALLELISM, 2, 1, 0);

        try (OneInputStreamOperatorTestHarness<Tuple2<String, Integer>, Object> testHarness =
                TestUtils.createRescalingTestSink(outDir, 1, 0, 100L, 10L)) {
            testHarness.setup();
            testHarness.initializeState(initState);
            testHarness.open();

            // still everything in-progress but the in-progress for prev task 1 should be put in
            // pending now
            TestUtils.checkLocalFs(outDir, 3, 0);

            testHarness.snapshot(2L, 2L);
            testHarness.notifyOfCompletedCheckpoint(2L);

            int counter = 0;
            for (Map.Entry<File, String> fileContents :
                    TestUtils.getFileContentByPath(outDir).entrySet()) {
                final String parentFilename = fileContents.getKey().getParentFile().getName();
                final String filename = fileContents.getKey().getName();

                if (parentFilename.equals("test1")) {
                    // the following is because it depends on the order in which the states are
                    // consumed in the initialize state.
                    if (filename.contains("-0.inprogress") || filename.endsWith("-0")) {
                        counter++;
                        assertThat(fileContents.getValue()).isIn("test1@1\n", "test1@0\n");
                    }
                } else if (parentFilename.equals("test2")
                        && filename.contains(".part-1-1.inprogress")) {
                    counter++;
                    assertThat(fileContents.getValue()).isEqualTo("test2@1\n");
                }
            }
            assertThat(counter).isEqualTo(3L);
        }
    }
}
