package xyz.gianlu.librespot.core;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.crypto.Packet;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author Gianlu
 */
public abstract class PacketsManager implements AutoCloseable {
	private static final Logger LOGGER = Logger.getLogger(PacketsManager.class);
	protected final Session session;
	private final BlockingQueue<Packet> queue;
	private final Looper looper;
	private final ExecutorService executorService;

	public PacketsManager(@NotNull Session session) {
		this.session = session;
		this.executorService = session.executor();
		this.queue = new LinkedBlockingQueue<>();
		this.looper = new Looper();
		new Thread(looper, "packets-manager-" + looper.hashCode()).start();
	}

	public final void dispatch(@NotNull Packet packet) {
		appendToQueue(packet);
	}

	@Override
	public void close() {
		looper.stop();
	}

	/**
	 * This method can be overridden to process packet synchronously. This MUST not block for a long period of time.
	 */
	protected void appendToQueue(@NotNull Packet packet) {
		queue.add(packet);
	}

	protected abstract void handle(@NotNull Packet packet) throws IOException;

	protected abstract void exception(@NotNull Exception ex);

	private final class Looper implements Runnable {
		private volatile boolean shouldStop = false;
		private Thread thread;

		@Override
		public void run() {
			LOGGER.trace("PacketsManager.Looper started");
			this.thread = Thread.currentThread();
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
				} catch (InterruptedException ignored) {
				}
			}
			LOGGER.trace("PacketsManager.Looper stopped");
		}

		void stop() {
			shouldStop = true;
			thread.interrupt();
		}
	}
}
