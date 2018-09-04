package org.librespot.spotify;

import org.librespot.spotify.proto.Authentication;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException, GeneralSecurityException, Session.SpotifyAuthenticationException {
        Session session = Session.create();
        session.connect();
        Authentication.APWelcome welcome = session.authenticateUserPass(args[0], args[1]);
        System.out.println(welcome);
    }
}
