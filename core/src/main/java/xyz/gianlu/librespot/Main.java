package xyz.gianlu.librespot;

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

    public static void main(String[] args) throws IOException, GeneralSecurityException, Session.SpotifyAuthenticationException, SpotifyIrc.IrcException, MercuryClient.PubSubException {
        Session session = new Session.Builder(Session.DeviceType.Computer, new FileConfiguration(new File("conf.properties")))
                // .facebook()
                // .zeroconf()
                .userPass(args[0], args[1])
                // .blob("username", new byte[] {...})
                .create();

        ConsoleClient.start(session);
    }
}
