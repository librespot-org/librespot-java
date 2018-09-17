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

    public static void main(String[] args) throws IOException, GeneralSecurityException, Session.SpotifyAuthenticationException, InterruptedException, SpotifyIrc.IrcException, MercuryClient.PubSubException {
        Session session = new Session.Builder(Session.DeviceType.Computer, "JavaTest")
                // .zeroconf()
                .userPass(args[0], args[1])
                // .blob("username", new byte[] {...})
                .create();

        SpotifyIrc spirc = new SpotifyIrc(session.mercury());
    }
}
