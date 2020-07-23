package xyz.gianlu.librespot;


import org.apache.logging.log4j.core.config.Configurator;
import xyz.gianlu.librespot.common.Log4JUncaughtExceptionHandler;

import java.io.IOException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException {
        FileConfiguration conf = new FileConfiguration(args);
        Configurator.setRootLevel(conf.loggingLevel());
        Thread.setDefaultUncaughtExceptionHandler(new Log4JUncaughtExceptionHandler());

        // TODO: Startup scripts
    }
}
