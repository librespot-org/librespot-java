package xyz.gianlu.librespot.api;


import org.apache.logging.log4j.core.config.Configurator;
import xyz.gianlu.librespot.AbsConfiguration;
import xyz.gianlu.librespot.FileConfiguration;
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

    public static void main(String[] args) throws IOException, MercuryClient.MercuryException, GeneralSecurityException, Session.SpotifyAuthenticationException {
        AbsConfiguration conf = new FileConfiguration(args);
        Configurator.setRootLevel(conf.loggingLevel());
        Thread.setDefaultUncaughtExceptionHandler(new Log4JUncaughtExceptionHandler());

        SessionWrapper wrapper;
        if (conf.authStrategy() == AuthConfiguration.Strategy.ZEROCONF && !conf.hasStoredCredentials())
            wrapper = SessionWrapper.fromZeroconf(ZeroconfServer.create(conf));
        else
            wrapper = SessionWrapper.fromSession(new Session.Builder(conf).create());

        ApiServer server = new ApiServer(conf, wrapper);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
    }
}
