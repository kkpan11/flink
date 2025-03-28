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

package org.apache.flink.table.types.extraction;

import org.apache.flink.table.types.extraction.ExtractionUtils.Autoboxing;

import org.apache.flink.shaded.guava33.com.google.common.collect.ImmutableList;

import org.junit.jupiter.api.Test;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/** Tests {@link ExtractionUtils}. */
public class ExtractionUtilsTest {

    @Test
    void testAutoboxing() {
        assertThat(ExtractionUtils.isAssignable(int.class, Integer.class, Autoboxing.STRICT))
                .isTrue();

        // In strict autoboxing this is not allowed
        assertThat(ExtractionUtils.isAssignable(Integer.class, int.class, Autoboxing.STRICT))
                .isFalse();

        assertThat(ExtractionUtils.isAssignable(Integer.class, int.class, Autoboxing.JVM)).isTrue();

        assertThat(ExtractionUtils.isAssignable(Integer.class, Number.class, Autoboxing.STRICT))
                .isTrue();
    }

    @Test
    void testResolveParameters() {
        List<Method> methods = ExtractionUtils.collectMethods(LongClass.class, "method");
        Method method = methods.get(0);
        Type longType =
                ExtractionUtils.resolveVariableWithClassContext(
                        LongClass.class, method.getGenericParameterTypes()[0]);
        Type futureType =
                ExtractionUtils.resolveVariableWithClassContext(
                        LongClass.class, method.getGenericParameterTypes()[1]);
        Type listOfFutures =
                ExtractionUtils.resolveVariableWithClassContext(
                        LongClass.class, method.getGenericParameterTypes()[2]);
        Type arrayType =
                ExtractionUtils.resolveVariableWithClassContext(
                        LongClass.class, method.getGenericParameterTypes()[3]);
        assertThat(longType).isEqualTo(Long.class);
        assertThat(futureType).isInstanceOf(ParameterizedType.class);
        assertThat(((ParameterizedType) futureType).getRawType())
                .isEqualTo(CompletableFuture.class);
        assertThat(((ParameterizedType) futureType).getActualTypeArguments()[0])
                .isEqualTo(Long.class);
        assertThat(listOfFutures).isInstanceOf(ParameterizedType.class);
        assertThat(((ParameterizedType) listOfFutures).getRawType()).isEqualTo(List.class);
        assertThat(((ParameterizedType) listOfFutures).getActualTypeArguments()[0])
                .isInstanceOf(ParameterizedType.class);
        ParameterizedType innerFuture =
                ((ParameterizedType)
                        ((ParameterizedType) listOfFutures).getActualTypeArguments()[0]);
        assertThat(innerFuture.getRawType()).isEqualTo(CompletableFuture.class);
        assertThat(innerFuture.getActualTypeArguments()[0]).isEqualTo(Long.class);
        assertThat(arrayType).isInstanceOf(GenericArrayType.class);
        assertThat(((GenericArrayType) arrayType).getGenericComponentType()).isEqualTo(Long.class);
    }

    @Test
    void testResolveParametersDeeper() {
        List<Method> methods = ExtractionUtils.collectMethods(FutureClass.class, "method");
        Method method = methods.get(0);
        Type futureType =
                ExtractionUtils.resolveVariableWithClassContext(
                        FutureClass.class, method.getGenericParameterTypes()[0]);
        Type listOfFutures =
                ExtractionUtils.resolveVariableWithClassContext(
                        FutureClass.class, method.getGenericParameterTypes()[1]);
        assertThat(futureType).isInstanceOf(ParameterizedType.class);
        assertThat(((ParameterizedType) futureType).getRawType())
                .isEqualTo(CompletableFuture.class);
        assertThat(((ParameterizedType) futureType).getActualTypeArguments()[0])
                .isEqualTo(Long.class);
        assertThat(listOfFutures).isInstanceOf(ParameterizedType.class);
        assertThat(((ParameterizedType) listOfFutures).getRawType()).isEqualTo(List.class);
        assertThat(((ParameterizedType) listOfFutures).getActualTypeArguments()[0])
                .isInstanceOf(ParameterizedType.class);
        ParameterizedType innerFuture =
                ((ParameterizedType)
                        ((ParameterizedType) listOfFutures).getActualTypeArguments()[0]);
        assertThat(innerFuture.getRawType()).isEqualTo(CompletableFuture.class);
        assertThat(innerFuture.getActualTypeArguments()[0]).isEqualTo(Long.class);
    }

    @Test
    void testExtractExecutableNamesWithMultiLocalVariableBlocks() {
        List<String> expectedParameterNames =
                ImmutableList.of("generic", "genericFuture", "listOfGenericFuture", "array");

        // test the local variable is not initialized at first
        List<Method> methods =
                ExtractionUtils.collectMethods(
                        MultiLocalVariableWithoutInitializationClass.class, "method");
        Method method = methods.get(0);
        List<String> parameterNames = ExtractionUtils.extractExecutableNames(method);
        assertThat(parameterNames).isEqualTo(expectedParameterNames);

        // test the local variable is initialized at first
        methods =
                ExtractionUtils.collectMethods(
                        MultiLocalVariableBlocksWithInitializationClass.class, "method");
        method = methods.get(0);
        parameterNames = ExtractionUtils.extractExecutableNames(method);
        assertThat(parameterNames).isEqualTo(expectedParameterNames);
    }

    @Test
    void testExtractExecutableNamesWithParameterNameShadowed() {
        List<String> expectedParameterNames =
                ImmutableList.of(
                        "generic", "result", "genericFuture", "listOfGenericFuture", "array");
        // test the local variable is not initialized at first
        List<Method> methods =
                ExtractionUtils.collectMethods(ParameterNameShadowedClass.class, "method");
        Method method = methods.get(0);
        List<String> parameterNames = ExtractionUtils.extractExecutableNames(method);
        assertThat(parameterNames).isEqualTo(expectedParameterNames);
    }

    /** Test function. */
    public static class ClassBase<T> {

        public void method(
                T generic,
                CompletableFuture<T> genericFuture,
                List<CompletableFuture<T>> listOfGenericFuture,
                T[] array) {}
    }

    /** Test function. */
    public static class LongClass extends ClassBase<Long> {}

    /** Test function. */
    public static class ClassBase2<T> {

        public void method(T generic, List<T> list) {}
    }

    /** Test function. */
    public static class FutureClass extends ClassBase2<CompletableFuture<Long>> {}

    /**
     * A test function that contains multi local variable blocks without initialization at first.
     */
    public static class MultiLocalVariableWithoutInitializationClass extends ClassBase<Long> {

        @Override
        public void method(
                Long generic,
                CompletableFuture<Long> genericFuture,
                List<CompletableFuture<Long>> listOfGenericFuture,
                Long[] array) {
            // don't initialize the local variable
            String localVariable;

            if (generic == null) {
                localVariable = "null";
            } else if (generic < 0) {
                localVariable = "negative";
            } else if (generic > 0) {
                localVariable = "positive";
            } else {
                localVariable = "zero";
            }

            // use the local variable
            System.err.println("localVariable: " + localVariable);
        }
    }

    /** A test function that contains multi local variable blocks with initialization at first. */
    public static class MultiLocalVariableBlocksWithInitializationClass extends ClassBase<Long> {

        @Override
        public void method(
                Long generic,
                CompletableFuture<Long> genericFuture,
                List<CompletableFuture<Long>> listOfGenericFuture,
                Long[] array) {
            // initialize the local variable
            String localVariable = "";

            if (generic == null) {
                localVariable = "null";
            } else if (generic < 0) {
                localVariable = "negative";
            } else if (generic > 0) {
                localVariable = "positive";
            } else {
                localVariable = "zero";
            }

            // use the local variable
            System.err.println("localVariable: " + localVariable);
        }
    }

    /**
     * A test function where one function parameter has the same name as a class member variable
     * within another complex function parameter.
     */
    public static class ParameterNameShadowedClass {

        @SuppressWarnings("unused")
        public void method(
                Long generic,
                // this `result` has the same name as the class member variable in
                // `CompletableFuture`
                Object result,
                CompletableFuture<Long> genericFuture,
                List<CompletableFuture<Long>> listOfGenericFuture,
                Long[] array) {}
    }
}
