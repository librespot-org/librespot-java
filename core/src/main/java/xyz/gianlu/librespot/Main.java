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

    public static void main(String[] args) throws IOException, GeneralSecurityException, Session.SpotifyAuthenticationException, SpotifyIrc.IrcException, MercuryClient.PubSubException, InterruptedException {
        Session session = new Session.Builder(new FileConfiguration(new File("conf.properties"), args)).create();

        Thread.sleep(8000);

        session.close();
    }
}
