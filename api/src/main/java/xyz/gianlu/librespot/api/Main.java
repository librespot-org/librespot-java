package xyz.gianlu.librespot.api;

import xyz.gianlu.librespot.FileConfiguration;
import xyz.gianlu.librespot.api.server.ApiServer;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.spirc.SpotifyIrc;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException, GeneralSecurityException, MercuryClient.PubSubException, SpotifyIrc.IrcException, Session.SpotifyAuthenticationException {
        Session session = new Session.Builder(Session.DeviceType.Computer, new FileConfiguration(new File("conf.properties")))
                .userPass(args[0], args[1])
                .create();

        ApiServer server = new ApiServer(24879);
        server.registerHandler(new PlayerHandler());
        server.registerHandler(new MetadataHandler(session));
        server.registerHandler(new MercuryHandler(session));
    }
}
