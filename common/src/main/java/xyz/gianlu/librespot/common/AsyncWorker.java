package xyz.gianlu.librespot.common;

import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Wrapper around AsyncProcessor that deals with void methods and does not expect a response type
 *
 * @param <T> Task type for processor
 */
public class AsyncWorker<T> implements Closeable {

    private final AsyncProcessor<T, Void> underlyingProcessor;

    public AsyncWorker(@NotNull String name, @NotNull Consumer<T> consumer) {
        this.underlyingProcessor = new AsyncProcessor<>(name, t -> {
           consumer.accept(t);
           return null;
        });
    }

    public Future<Void> submit(@NotNull T task) {
        return underlyingProcessor.submit(task);
    }

    @Override
    public void close() {
        underlyingProcessor.close();
    }
}
