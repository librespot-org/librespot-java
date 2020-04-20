package xyz.gianlu.librespot.common;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public class AsyncProcessorTest {

    @Test
    void testAsyncProcessor() {
        AtomicInteger internalState = new AtomicInteger();
        AsyncProcessor<Integer, Integer> asyncProcessor = new AsyncProcessor<>("test-processor-1", internalState::addAndGet);
        asyncProcessor.submit(1);
        asyncProcessor.submit(2);
        asyncProcessor.submit(3);
        CompletableFuture<Integer> lastTask = asyncProcessor.submit(4);

        Integer lastResult = lastTask.join(); // we only need to wait for the last one as tasks are executed in order
        Assertions.assertEquals(10, internalState.get());
        Assertions.assertEquals(10, lastResult);
        asyncProcessor.close();
    }

    @Test
    void testAsyncProcessorExceptionHandling() {
        AsyncProcessor<Integer, Integer> asyncProcessor = new AsyncProcessor<>("test-processor-2", i -> {
            throw new IllegalStateException();
        });

        CompletableFuture<Integer> firstTask = asyncProcessor.submit(1);
        Assertions.assertThrows(ExecutionException.class, firstTask::get);

        // now we check our loop didn't break and we are able to submit more tasks to our queue
        CompletableFuture<Integer> secondTask = asyncProcessor.submit(1);
        Assertions.assertThrows(ExecutionException.class, secondTask::get);
        asyncProcessor.close();
    }

    @Test
    void testAsyncProcessorFailAfterShutdown() {
        AtomicInteger internalState = new AtomicInteger();
        AsyncProcessor<Integer, Integer> asyncProcessor = new AsyncProcessor<>("test-processor-3", internalState::addAndGet);

        CompletableFuture<Integer> taskBeforeShutdown = asyncProcessor.submit(1);
        Assertions.assertEquals(1, taskBeforeShutdown.join());

        asyncProcessor.close();

        IllegalStateException underlyingException = Assertions.assertThrows(IllegalStateException.class, () -> asyncProcessor.submit(1));
        Assertions.assertEquals("AsyncProcessor test-processor-3 has already been shutdown", underlyingException.getMessage());
    }

}
