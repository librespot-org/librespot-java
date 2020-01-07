package xyz.gianlu.librespot.api;

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
        ApiServer server = new ApiServer(conf);
        if (conf.authStrategy() == AuthConfiguration.Strategy.ZEROCONF) {
            ZeroconfServer.create(conf).addSessionListener(server::restart);
        } else {
            server.start(new Session.Builder(conf).create());
        }
    }
}
