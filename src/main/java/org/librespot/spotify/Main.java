package org.librespot.spotify;

import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.mercury.MercuryClient;
import org.librespot.spotify.mercury.MercuryRequests;
import org.librespot.spotify.mercury.OnResult;
import org.librespot.spotify.mercury.model.PlaylistId;
import org.librespot.spotify.mercury.model.TrackId;
import org.librespot.spotify.proto.Metadata;
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
        client.request(MercuryRequests.getRootPlaylists(args[0]), new OnResult<Playlist4Changes.SelectedListContent>() {
            @Override
            public void result(Playlist4Changes.@NotNull SelectedListContent result) {
                System.out.println(result);

                PlaylistId id = new PlaylistId(result.getContents().getItems(0));
                client.request(MercuryRequests.getPlaylist(id), new OnResult<Playlist4Changes.SelectedListContent>() {
                    @Override
                    public void result(Playlist4Changes.@NotNull SelectedListContent result) {
                        System.out.println(result);

                        TrackId id = new TrackId(result.getContents().getItems(0));
                        client.request(MercuryRequests.getTrack(id), new OnResult<Metadata.Track>() {
                            @Override
                            public void result(Metadata.@NotNull Track result) {
                                System.out.println(result);
                            }

                            @Override
                            public void failed(@NotNull Exception ex) {
                                ex.printStackTrace();
                            }
                        });
                    }

                    @Override
                    public void failed(@NotNull Exception ex) {
                        ex.printStackTrace();
                    }
                });
            }

            @Override
            public void failed(@NotNull Exception ex) {
                ex.printStackTrace();
            }
        });
    }
}
