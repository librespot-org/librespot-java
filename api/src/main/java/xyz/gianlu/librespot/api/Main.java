package xyz.gianlu.librespot.api;

import xyz.gianlu.librespot.AbsConfiguration;
import xyz.gianlu.librespot.FileConfiguration;
import xyz.gianlu.librespot.api.server.ApiServer;
import xyz.gianlu.librespot.api.server.ZeroconfApiServer;
import xyz.gianlu.librespot.core.AuthConfiguration;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.ZeroconfServer;
import xyz.gianlu.librespot.spirc.SpotifyIrc;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException, GeneralSecurityException, SpotifyIrc.IrcException, Session.SpotifyAuthenticationException {
        AbsConfiguration conf = new FileConfiguration(args);
        if (conf.authStrategy() == AuthConfiguration.Strategy.ZEROCONF) {
            ZeroconfServer zeroconfServer = ZeroconfServer.create(conf);

            ZeroconfApiServer zeroconfApiServer = new ZeroconfApiServer(24879);
            zeroconfServer.addSessionListener(zeroconfApiServer);
        } else {
            Session session = new Session.Builder(new FileConfiguration(args)).create();

            ApiServer server = new ApiServer(24879);
            server.registerHandler(new PlayerHandler(session));
            server.registerHandler(new MetadataHandler(session));
            server.registerHandler(new MercuryHandler(session));
        }
    }
}
