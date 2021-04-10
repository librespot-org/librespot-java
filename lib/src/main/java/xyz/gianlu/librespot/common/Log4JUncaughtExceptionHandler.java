package xyz.gianlu.librespot.common;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author devgianlu
 */
public class Log4JUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(Log4JUncaughtExceptionHandler.class);

    @Override
    public void uncaughtException(@NotNull Thread t, Throwable e) {
        LOGGER.error("[{}]", t.getName(), e);
    }
}
