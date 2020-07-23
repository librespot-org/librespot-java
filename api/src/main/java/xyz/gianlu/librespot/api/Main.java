package xyz.gianlu.librespot.api;


import org.apache.logging.log4j.core.config.Configurator;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Log4JUncaughtExceptionHandler;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.player.FileConfiguration;
import xyz.gianlu.librespot.player.FileConfiguration.AuthStrategy;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException, MercuryClient.MercuryException, GeneralSecurityException, Session.SpotifyAuthenticationException {
        FileConfiguration conf = new FileConfiguration(args);
        Configurator.setRootLevel(conf.loggingLevel());
        Thread.setDefaultUncaughtExceptionHandler(new Log4JUncaughtExceptionHandler());

        String host = conf.apiHost();
        int port = conf.apiPort();

        if (args.length > 0 && args[0].equals("noPlayer")) withoutPlayer(port, host, conf);
        else withPlayer(port, host, conf);
    }

    private static void withPlayer(int port, @NotNull String host, @NotNull FileConfiguration conf) throws IOException, MercuryClient.MercuryException, GeneralSecurityException, Session.SpotifyAuthenticationException {
        PlayerWrapper wrapper;
        if (conf.authStrategy() == AuthStrategy.ZEROCONF)
            wrapper = PlayerWrapper.fromZeroconf(conf.initZeroconfBuilder().create(), conf.toPlayer());
        else
            wrapper = PlayerWrapper.fromSession(conf.initSessionBuilder().create(), conf.toPlayer());

        PlayerApiServer server = new PlayerApiServer(port, host, wrapper);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
    }

    private static void withoutPlayer(int port, @NotNull String host, @NotNull FileConfiguration conf) throws IOException, MercuryClient.MercuryException, GeneralSecurityException, Session.SpotifyAuthenticationException {
        SessionWrapper wrapper;
        if (conf.authStrategy() == AuthStrategy.ZEROCONF)
            wrapper = SessionWrapper.fromZeroconf(conf.initZeroconfBuilder().create());
        else
            wrapper = SessionWrapper.fromSession(conf.initSessionBuilder().create());

        ApiServer server = new ApiServer(port, host, wrapper);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
    }
}
