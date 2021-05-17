/*
 * Copyright 2021 devgianlu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.gianlu.librespot.common;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public class AsyncProcessorTest {

    @Test
    void testAsyncProcessor() throws ExecutionException, InterruptedException {
        AtomicInteger internalState = new AtomicInteger();
        AsyncProcessor<Integer, Integer> asyncProcessor = new AsyncProcessor<>("test-processor-1", internalState::addAndGet);
        asyncProcessor.submit(1);
        asyncProcessor.submit(2);
        asyncProcessor.submit(3);
        Future<Integer> lastTask = asyncProcessor.submit(4);

        Integer lastResult = lastTask.get(); // we only need to wait for the last one as tasks are executed in order
        Assertions.assertEquals(10, internalState.get());
        Assertions.assertEquals(10, lastResult);
        asyncProcessor.close();
    }

    @Test
    void testAsyncProcessorExceptionHandling() {
        AsyncProcessor<Integer, Integer> asyncProcessor = new AsyncProcessor<>("test-processor-2", i -> {
            throw new IllegalStateException();
        });

        Future<Integer> firstTask = asyncProcessor.submit(1);
        Assertions.assertThrows(ExecutionException.class, firstTask::get);

        // now we check our loop didn't break and we are able to submit more tasks to our queue
        Future<Integer> secondTask = asyncProcessor.submit(1);
        Assertions.assertThrows(ExecutionException.class, secondTask::get);
        asyncProcessor.close();
    }

    @Test
    void testAsyncProcessorFailAfterShutdown() throws ExecutionException, InterruptedException {
        AtomicInteger internalState = new AtomicInteger();
        AsyncProcessor<Integer, Integer> asyncProcessor = new AsyncProcessor<>("test-processor-3", internalState::addAndGet);

        Future<Integer> taskBeforeShutdown = asyncProcessor.submit(1);
        Assertions.assertEquals(1, taskBeforeShutdown.get());

        asyncProcessor.close();

        Assertions.assertThrows(RejectedExecutionException.class, () -> asyncProcessor.submit(1));
    }
}
