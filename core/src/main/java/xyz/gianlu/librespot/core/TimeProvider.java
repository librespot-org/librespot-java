package xyz.gianlu.librespot.core;

import org.apache.log4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Gianlu
 */
public final class TimeProvider {
    private static final AtomicLong offset = new AtomicLong(0);
    private static final Logger LOGGER = Logger.getLogger(TimeProvider.class);

    private TimeProvider() {
    }

    public static void init(int delta) {
        synchronized (offset) {
            offset.set(delta * 1000);
            LOGGER.debug(String.format("Corrected time offset, delta: %ds", delta));
        }
    }

    public static long currentTimeMillis() {
        synchronized (offset) {
            return System.currentTimeMillis() + offset.get();
        }
    }
}
