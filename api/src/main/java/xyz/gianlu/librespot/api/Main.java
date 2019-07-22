package xyz.gianlu.librespot.api;

import xyz.gianlu.librespot.FileConfiguration;
import xyz.gianlu.librespot.api.server.ApiServer;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException, GeneralSecurityException, Session.SpotifyAuthenticationException, MercuryClient.MercuryException {
        Session session = new Session.Builder(new FileConfiguration(args)).create();

        ApiServer server = new ApiServer(24879);
        server.registerHandler(new PlayerHandler(session));
        server.registerHandler(new MetadataHandler(session));
        server.registerHandler(new MercuryHandler(session));
    }
}
