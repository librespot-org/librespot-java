package org.librespot.spotify;

import org.librespot.spotify.core.Session;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException, GeneralSecurityException, Session.SpotifyAuthenticationException, InterruptedException {
        Session session = new Session.Builder(Session.DeviceType.Computer, "JavaTest")
                .zeroconf()
                // .userPass("username", "password")
                // .blob("username", new byte[] {...})
                .create();

        System.out.println(session.apWelcome());
    }
}
