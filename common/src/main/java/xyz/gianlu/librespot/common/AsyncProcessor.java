package xyz.gianlu.librespot.common;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;

/**
 * Simple worker thread that processes tasks sequentially
 * @param <REQ> The type of task/input that AsyncProcessor handles.
 * @param <RES> Return type of our processor implementation
 */
public class AsyncProcessor<REQ, RES> implements Closeable, Runnable {

    private static final Logger LOGGER = Logger.getLogger(AsyncProcessor.class);
    private final Thread thread;
    private final BlockingQueue<FutureHolder> internalQueue;
    private final String name;
    private final Function<REQ, RES> processor;
    private volatile boolean running = true;

    /**
     * @param name name of async processor - used for thread name and logging
     * @param processor actual processing implementation ran on background thread
     */
    public AsyncProcessor(@NotNull String name, @NotNull Function<REQ, RES> processor) {
        this.name = name;
        this.processor = processor;

        internalQueue = new LinkedBlockingQueue<>();
        thread = new Thread(this, name);
        thread.start();
    }

    public CompletableFuture<RES> submit(@NotNull REQ task) {
        if (!running) {
            throw new IllegalStateException(String.format("AsyncProcessor %s has already been shutdown", name));
        }
        CompletableFuture<RES> completableFuture = new CompletableFuture<>();
        internalQueue.add(new FutureHolder(task, completableFuture));
        return completableFuture;
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }

    @Override
    public void run() {
        LOGGER.trace(String.format("AsyncProcessor %s is starting", name));

        while (running) {
            try {
                // A strong argument could be made here that you could just create a single threaded executor
                // and submit futures against it. However a queue based implementation gives us options for dropping
                // duplicate tasks, depth metrics etc in the future.
                FutureHolder polled = internalQueue.take();

                try {
                    RES result = processor.apply(polled.getTask());
                    polled.getCompletableFuture().complete(result);
                } catch (Exception e) {
                    LOGGER.warn(String.format("AsyncProcessor %s received an unexpected exception, ignoring:", name), e);
                    polled.getCompletableFuture().completeExceptionally(e);
                }
            } catch (InterruptedException ignored) {
                // running will have been set to false and we exit the loop
                LOGGER.trace(String.format("AsyncProcessor %s was interrupted", name));
            }
        }

        LOGGER.trace(String.format("AsyncProcessor %s is shutting down", name));
    }

    private class FutureHolder {

        private final REQ task;
        private final CompletableFuture<RES> completableFuture;

        public FutureHolder(REQ task, CompletableFuture<RES> completableFuture) {
            this.task = task;
            this.completableFuture = completableFuture;
        }

        public REQ getTask() {
            return task;
        }

        public CompletableFuture<RES> getCompletableFuture() {
            return completableFuture;
        }
    }

}
