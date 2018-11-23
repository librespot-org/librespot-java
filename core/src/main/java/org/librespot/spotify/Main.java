package org.librespot.spotify;

import org.librespot.spotify.core.Session;
import org.librespot.spotify.mercury.MercuryClient;
import org.librespot.spotify.spirc.SpotifyIrc;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException, GeneralSecurityException, Session.SpotifyAuthenticationException, SpotifyIrc.IrcException, MercuryClient.PubSubException {
        Session session = new Session.Builder(Session.DeviceType.Computer, "librespot-java", new DefaultConfiguration())
                // .facebook()
                // .zeroconf()
                .userPass(args[0], args[1])
                // .blob("username", new byte[] {...})
                .create();

        ConsoleClient.start(session);
    }
}
