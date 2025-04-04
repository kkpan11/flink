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

package org.apache.flink.datastream.impl.operators;

import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.datastream.api.common.Collector;
import org.apache.flink.datastream.api.context.NonPartitionedContext;
import org.apache.flink.datastream.api.context.PartitionedContext;
import org.apache.flink.datastream.api.function.TwoInputNonBroadcastStreamProcessFunction;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.asyncprocessing.AsyncKeyedTwoInputStreamOperatorTestHarness;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link KeyedTwoInputNonBroadcastProcessOperator}. */
class KeyedTwoInputNonBroadcastProcessOperatorTest {
    @Test
    void testProcessRecord() throws Exception {
        KeyedTwoInputNonBroadcastProcessOperator<Long, Integer, Long, Long> processOperator =
                new KeyedTwoInputNonBroadcastProcessOperator<>(
                        new TwoInputNonBroadcastStreamProcessFunction<Integer, Long, Long>() {
                            @Override
                            public void processRecordFromFirstInput(
                                    Integer record,
                                    Collector<Long> output,
                                    PartitionedContext<Long> ctx) {
                                output.collect(Long.valueOf(record));
                            }

                            @Override
                            public void processRecordFromSecondInput(
                                    Long record,
                                    Collector<Long> output,
                                    PartitionedContext<Long> ctx) {
                                output.collect(record);
                            }
                        });

        try (AsyncKeyedTwoInputStreamOperatorTestHarness<Long, Integer, Long, Long> testHarness =
                AsyncKeyedTwoInputStreamOperatorTestHarness.create(
                        processOperator,
                        (KeySelector<Integer, Long>) (data) -> (long) (data + 1),
                        (KeySelector<Long, Long>) value -> value + 1,
                        Types.LONG)) {
            testHarness.open();
            testHarness.processElement1(new StreamRecord<>(1));
            testHarness.processElement2(new StreamRecord<>(2L));
            testHarness.processElement2(new StreamRecord<>(4L));
            testHarness.processElement1(new StreamRecord<>(3));
            Collection<StreamRecord<Long>> recordOutput = testHarness.getRecordOutput();
            assertThat(recordOutput)
                    .containsExactly(
                            new StreamRecord<>(1L),
                            new StreamRecord<>(2L),
                            new StreamRecord<>(4L),
                            new StreamRecord<>(3L));
        }
    }

    @Test
    void testEndInput() throws Exception {
        AtomicInteger firstInputCounter = new AtomicInteger();
        AtomicInteger secondInputCounter = new AtomicInteger();
        KeyedTwoInputNonBroadcastProcessOperator<Long, Integer, Long, Long> processOperator =
                new KeyedTwoInputNonBroadcastProcessOperator<>(
                        new TwoInputNonBroadcastStreamProcessFunction<Integer, Long, Long>() {
                            @Override
                            public void processRecordFromFirstInput(
                                    Integer record,
                                    Collector<Long> output,
                                    PartitionedContext<Long> ctx) {
                                // do nothing.
                            }

                            @Override
                            public void processRecordFromSecondInput(
                                    Long record,
                                    Collector<Long> output,
                                    PartitionedContext<Long> ctx) {
                                // do nothing.
                            }

                            @Override
                            public void endFirstInput(NonPartitionedContext<Long> ctx) {
                                try {
                                    ctx.applyToAllPartitions(
                                            (out, context) -> {
                                                firstInputCounter.incrementAndGet();
                                                Long currentKey =
                                                        context.getStateManager().getCurrentKey();
                                                out.collect(currentKey);
                                            });
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }

                            @Override
                            public void endSecondInput(NonPartitionedContext<Long> ctx) {
                                try {
                                    ctx.applyToAllPartitions(
                                            (out, context) -> {
                                                secondInputCounter.incrementAndGet();
                                                Long currentKey =
                                                        context.getStateManager().getCurrentKey();
                                                out.collect(currentKey);
                                            });
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        });

        try (AsyncKeyedTwoInputStreamOperatorTestHarness<Long, Integer, Long, Long> testHarness =
                AsyncKeyedTwoInputStreamOperatorTestHarness.create(
                        processOperator,
                        (KeySelector<Integer, Long>) Long::valueOf,
                        (KeySelector<Long, Long>) value -> value,
                        Types.LONG)) {
            testHarness.open();
            testHarness.processElement1(new StreamRecord<>(1)); // key is 1L
            testHarness.processElement2(new StreamRecord<>(2L)); // key is 2L
            testHarness.endInput1();
            assertThat(firstInputCounter).hasValue(2);
            Collection<StreamRecord<Long>> recordOutput = testHarness.getRecordOutput();
            assertThat(recordOutput)
                    .containsExactly(new StreamRecord<>(1L), new StreamRecord<>(2L));
            testHarness.processElement2(new StreamRecord<>(3L)); // key is 3L
            testHarness.getOutput().clear();
            testHarness.endInput2();
            assertThat(secondInputCounter).hasValue(3);
            recordOutput = testHarness.getRecordOutput();
            assertThat(recordOutput)
                    .containsExactly(
                            new StreamRecord<>(1L), new StreamRecord<>(2L), new StreamRecord<>(3L));
        }
    }

    @Test
    void testCheckKey() throws Exception {
        KeyedTwoInputNonBroadcastProcessOperator<Long, Integer, Long, Long> processOperator =
                new KeyedTwoInputNonBroadcastProcessOperator<>(
                        new TwoInputNonBroadcastStreamProcessFunction<Integer, Long, Long>() {
                            @Override
                            public void processRecordFromFirstInput(
                                    Integer record,
                                    Collector<Long> output,
                                    PartitionedContext<Long> ctx) {
                                output.collect(Long.valueOf(record));
                            }

                            @Override
                            public void processRecordFromSecondInput(
                                    Long record,
                                    Collector<Long> output,
                                    PartitionedContext<Long> ctx) {
                                output.collect(record);
                            }
                        },
                        // -1 is an invalid key in this suite.
                        (out) -> -1L);

        try (AsyncKeyedTwoInputStreamOperatorTestHarness<Long, Integer, Long, Long> testHarness =
                AsyncKeyedTwoInputStreamOperatorTestHarness.create(
                        processOperator,
                        (KeySelector<Integer, Long>) Long::valueOf,
                        (KeySelector<Long, Long>) value -> value,
                        Types.LONG)) {
            testHarness.open();
            assertThatThrownBy(() -> testHarness.processElement1(new StreamRecord<>(1)))
                    .isInstanceOf(IllegalStateException.class);
        }
        try (AsyncKeyedTwoInputStreamOperatorTestHarness<Long, Integer, Long, Long> testHarness =
                AsyncKeyedTwoInputStreamOperatorTestHarness.create(
                        processOperator,
                        (KeySelector<Integer, Long>) Long::valueOf,
                        (KeySelector<Long, Long>) value -> value,
                        Types.LONG)) {
            testHarness.open();
            assertThatThrownBy(() -> testHarness.processElement2(new StreamRecord<>(1L)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
