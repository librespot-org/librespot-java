package xyz.gianlu.librespot.common;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Simple worker thread that processes tasks sequentially
 * @param <REQ> The type of task/input that AsyncProcessor handles.
 * @param <RES> Return type of our processor implementation
 */
public class AsyncProcessor<REQ, RES> implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(AsyncProcessor.class);
    private final String name;
    private final Function<REQ, RES> processor;
    private final ExecutorService executor;
    private volatile boolean running;

    /**
     * @param name name of async processor - used for thread name and logging
     * @param processor actual processing implementation ran on background thread
     */
    public AsyncProcessor(@NotNull String name, @NotNull Function<REQ, RES> processor) {
        executor = Executors.newSingleThreadExecutor(new NameThreadFactory(r -> name));
        this.name = name;
        this.processor = processor;
        running = true;
        LOGGER.trace(String.format("AsyncProcessor %s has started", name));
    }

    public Future<RES> submit(@NotNull REQ task) {
        if (!running) {
            throw new IllegalStateException(String.format("AsyncProcessor %s has already been shutdown", name));
        }
        return executor.submit(() -> processor.apply(task));
    }

    @Override
    public void close() {
        running = false;
        LOGGER.trace(String.format("AsyncProcessor %s is shutting down", name));
        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            LOGGER.trace(String.format("AsyncProcessor %s was interrupted during shutdown", name));
        }
        LOGGER.trace(String.format("AsyncProcessor %s is shut down", name));
    }

}
