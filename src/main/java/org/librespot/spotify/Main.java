package org.librespot.spotify;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException, GeneralSecurityException, Session.SpotifyAuthenticationException {
        Configuration.init();

        Session session = Session.create(Session.DeviceType.Computer, "JavaTest");
        session.connect();
        ZeroconfAuthenticator authenticator = session.authenticateZeroconf();
    }
}
