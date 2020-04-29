package xyz.gianlu.librespot.common;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * @author devgianlu
 */
public class Log4JUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {
    private static final Logger LOGGER = LogManager.getLogger(Log4JUncaughtExceptionHandler.class);

    @Override
    public void uncaughtException(@NotNull Thread t, Throwable e) {
        LOGGER.fatal("[{}]", t.getName(), e);
    }
}
