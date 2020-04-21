package xyz.gianlu.librespot.common;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Simple worker thread that processes tasks sequentially
 *
 * @param <REQ> The type of task/input that AsyncProcessor handles.
 * @param <RES> Return type of our processor implementation
 */
public class AsyncProcessor<REQ, RES> implements Closeable {
    private static final Logger LOGGER = Logger.getLogger(AsyncProcessor.class);
    private final String name;
    private final Function<REQ, RES> processor;
    private final ExecutorService executor;

    /**
     * @param name      name of async processor - used for thread name and logging
     * @param processor actual processing implementation ran on background thread
     */
    public AsyncProcessor(@NotNull String name, @NotNull Function<REQ, RES> processor) {
        executor = Executors.newSingleThreadExecutor(new NameThreadFactory(r -> name));
        this.name = name;
        this.processor = processor;
        LOGGER.trace(String.format("AsyncProcessor{%s} has started", name));
    }

    public Future<RES> submit(@NotNull REQ task) {
        return executor.submit(() -> processor.apply(task));
    }

    public boolean awaitTermination(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
        if (!executor.isShutdown())
            throw new IllegalStateException(String.format("AsyncProcessor{%s} hasn't been shut down yet", name));

        if (executor.awaitTermination(timeout, unit)) {
            LOGGER.trace(String.format("AsyncProcessor{%s} is shut down", name));
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void close() {
        LOGGER.trace(String.format("AsyncProcessor{%s} is shutting down", name));
        executor.shutdown();
    }
}
