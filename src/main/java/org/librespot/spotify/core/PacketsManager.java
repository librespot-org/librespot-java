package org.librespot.spotify.core;

import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.crypto.Packet;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author Gianlu
 */
public abstract class PacketsManager implements AutoCloseable {
    protected final Session session;
    private final BlockingQueue<Packet> queue;
    private final Looper looper;
    private final ExecutorService executorService;

    public PacketsManager(@NotNull Session session) {
        this.session = session;
        this.executorService = session.executor();
        this.queue = new LinkedBlockingQueue<>();
        this.looper = new Looper();
        new Thread(looper).start();
    }

    public final void dispatch(@NotNull Packet packet) {
        queue.add(packet);
    }

    @Override
    public void close() {
        looper.stop();
    }

    protected abstract void handle(@NotNull Packet packet) throws IOException;

    protected abstract void exception(@NotNull Exception ex);


    private static final class LooperException extends Exception {
        private LooperException(Throwable cause) {
            super(cause);
        }
    }

    private final class Looper implements Runnable {
        private volatile boolean shouldStop = false;

        @Override
        public void run() {
            while (!shouldStop) {
                try {
                    Packet packet = queue.take();
                    executorService.execute(() -> {
                        try {
                            handle(packet);
                        } catch (IOException ex) {
                            exception(ex);
                        }
                    });
                } catch (InterruptedException ex) {
                    executorService.execute(() -> exception(new LooperException(ex)));
                }
            }
        }

        void stop() {
            shouldStop = true;
        }
    }
}
