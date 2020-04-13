package xyz.gianlu.librespot.common;

import org.apache.log4j.Logger;

import java.io.Closeable;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

public class AsyncWorker<T> implements Closeable, Runnable {

    private static final Logger LOGGER = Logger.getLogger(AsyncWorker.class);
    private final Thread thread;

    private volatile boolean running = true;
    private final BlockingQueue<T> internalQueue;
    private final String name;
    private final Consumer<T> consumer;

    public AsyncWorker(String name, Consumer<T> consumer) {
        this.name = name;
        this.consumer = consumer;
        internalQueue = new LinkedBlockingQueue<>();
        thread = new Thread(this, name);
        thread.start();
    }

    public void submit(T task) {
        internalQueue.add(task);
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }

    @Override
    public void run() {
        while(running) {
            try {
                T polled = internalQueue.take();
                try {
                    consumer.accept(polled);
                } catch (Exception e) {
                    LOGGER.error(String.format("Async worker (%s) caught exception", name), e);
                }
            } catch (InterruptedException e) {
                LOGGER.info(String.format("Async worker (%s) was interrupted, ignoring", name));
            }
        }
        LOGGER.info(String.format("Async worker (%s) is shutting down", name));
    }

}
