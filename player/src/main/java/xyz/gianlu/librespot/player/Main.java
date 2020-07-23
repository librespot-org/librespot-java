package xyz.gianlu.librespot.player;


import org.apache.logging.log4j.core.config.Configurator;
import xyz.gianlu.librespot.FileConfiguration;
import xyz.gianlu.librespot.ZeroconfServer;
import xyz.gianlu.librespot.common.Log4JUncaughtExceptionHandler;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException, GeneralSecurityException, Session.SpotifyAuthenticationException, MercuryClient.MercuryException {
        FileConfiguration conf = new FileConfiguration(args);
        Configurator.setRootLevel(conf.loggingLevel());
        Thread.setDefaultUncaughtExceptionHandler(new Log4JUncaughtExceptionHandler());

        if (conf.authStrategy() == FileConfiguration.AuthStrategy.ZEROCONF) {
            ZeroconfServer server = conf.initZeroconfBuilder().create();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.closeSession();
                    server.close();
                } catch (IOException ignored) {
                }
            }));
        } else {
            Session session = conf.initSessionBuilder().create();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    session.close();
                } catch (IOException ignored) {
                }
            }));
        }
    }
}
