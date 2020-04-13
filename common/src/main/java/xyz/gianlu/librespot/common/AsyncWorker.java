package xyz.gianlu.librespot.common;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

public class AsyncWorker<T> implements Closeable, Runnable {
    private static final Logger LOGGER = Logger.getLogger(AsyncWorker.class);
    private final Thread thread;
    private final BlockingQueue<T> internalQueue;
    private final String name;
    private final Consumer<T> consumer;
    private volatile boolean running = true;

    public AsyncWorker(@NotNull String name, @NotNull Consumer<T> consumer) {
        this.name = name;
        this.consumer = consumer;

        internalQueue = new LinkedBlockingQueue<>();
        thread = new Thread(this, name);
        thread.start();
    }

    public void submit(@NotNull T task) {
        internalQueue.add(task);
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }

    @Override
    public void run() {
        while (running) {
            try {
                T polled = internalQueue.take();
                consumer.accept(polled);
            } catch (InterruptedException ignored) {
            }
        }

        LOGGER.trace(String.format("AsyncWorker{%s} is shutting down", name));
    }
}
