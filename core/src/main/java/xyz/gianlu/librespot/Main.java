package xyz.gianlu.librespot;

import xyz.gianlu.librespot.common.proto.Metadata;
import xyz.gianlu.librespot.core.AuthConfiguration;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.ZeroconfServer;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.model.EpisodeId;
import xyz.gianlu.librespot.spirc.SpotifyIrc;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException, GeneralSecurityException, Session.SpotifyAuthenticationException, SpotifyIrc.IrcException {
        AbsConfiguration conf = new FileConfiguration(new File("conf.properties"), args);
        if (conf.authStrategy() == AuthConfiguration.Strategy.ZEROCONF) {
            ZeroconfServer.create(conf);
        } else {
            Session s = new Session.Builder(conf).create();

            MercuryClient.ProtoWrapperResponse<Metadata.Episode> resp;
            try {
                resp = s.mercury().sendSync(MercuryRequests.getEpisode(EpisodeId.fromHex("b059c4117d3e518ea00a83a4a060a063")));
            } catch (MercuryClient.MercuryException e) {
                e.printStackTrace();
                return;
            }

            System.out.println(resp.json());
        }
    }
}
