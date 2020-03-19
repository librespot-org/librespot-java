package xyz.gianlu.librespot.api;

import org.apache.log4j.LogManager;
import xyz.gianlu.librespot.AbsConfiguration;
import xyz.gianlu.librespot.FileConfiguration;
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
        LogManager.getRootLogger().setLevel(conf.loggingLevel());

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
