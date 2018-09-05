package org.librespot.spotify;

import com.google.protobuf.InvalidProtocolBufferException;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.connection.MercuryClient;
import org.librespot.spotify.connection.Session;
import org.librespot.spotify.proto.Playlist4Changes;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException, GeneralSecurityException, Session.SpotifyAuthenticationException {
        Session session = Session.create();
        session.connect();
        session.authenticateUserPass(args[0], args[1]);

        MercuryClient client = session.mercury();
        client.send(String.format("hm://playlist/user/%s/rootlist", args[0]), MercuryClient.Method.GET, new byte[0][], new MercuryClient.Callback() {
            @Override
            public void response(MercuryClient.@NotNull Response response) {
                System.out.println(response);

                try {
                    Playlist4Changes.SelectedListContent list = Playlist4Changes.SelectedListContent.parseFrom(response.payload[0]);
                    System.out.println(list);
                } catch (InvalidProtocolBufferException e) {
                    e.printStackTrace();
                }
            }
        });
    }
}
