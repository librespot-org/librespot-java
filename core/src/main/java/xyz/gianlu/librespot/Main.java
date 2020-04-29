package xyz.gianlu.librespot;


import org.apache.logging.log4j.core.config.Configurator;
import xyz.gianlu.librespot.common.Log4JUncaughtExceptionHandler;
import xyz.gianlu.librespot.core.AuthConfiguration;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.ZeroconfServer;
import xyz.gianlu.librespot.mercury.MercuryClient;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException, GeneralSecurityException, Session.SpotifyAuthenticationException, MercuryClient.MercuryException {
        AbsConfiguration conf = new FileConfiguration(args);
        Configurator.setRootLevel(conf.loggingLevel());
        Thread.setDefaultUncaughtExceptionHandler(new Log4JUncaughtExceptionHandler());

        if (conf.authStrategy() == AuthConfiguration.Strategy.ZEROCONF && !conf.hasStoredCredentials()) {
            ZeroconfServer server = ZeroconfServer.create(conf);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.closeSession();
                    server.close();
                } catch (IOException ignored) {
                }
            }));
        } else {
            Session session = new Session.Builder(conf).create();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    session.close();
                } catch (IOException ignored) {
                }
            }));
        }
    }
}
