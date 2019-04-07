package xyz.gianlu.librespot.player.tracks;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.model.TrackId;
import xyz.gianlu.librespot.player.Player;
import xyz.gianlu.librespot.player.remote.Remote3Track;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * @author Gianlu
 */
public class PlaylistProvider implements TracksProvider {
    private static final Logger LOGGER = Logger.getLogger(PlaylistProvider.class);
    private final Spirc.State.Builder state;
    private final Player.Configuration conf;
    private final MercuryClient mercury;
    private long shuffleSeed = 0;

    public PlaylistProvider(@NotNull Session session, @NotNull Spirc.State.Builder state, @NotNull Player.Configuration conf) {
        this.state = state;
        this.conf = conf;
        this.mercury = session.mercury();
    }

    private static int[] getShuffleExchanges(int size, long seed) {
        int[] exchanges = new int[size - 1];
        Random rand = new Random(seed);
        for (int i = size - 1; i > 0; i--) {
            int n = rand.nextInt(i + 1);
            exchanges[size - 1 - i] = n;
        }
        return exchanges;
    }

    public void shuffleTracks(@NotNull Random random, boolean fully) {
        shuffleSeed = random.nextLong();

        List<Spirc.TrackRef> tracks = new ArrayList<>(state.getTrackList());
        if (fully) {
            Collections.shuffle(tracks, new Random(shuffleSeed));
        } else {
            if (state.getPlayingTrackIndex() != 0) {
                Collections.swap(tracks, 0, state.getPlayingTrackIndex());
                state.setPlayingTrackIndex(0);
            }

            int size = tracks.size() - 1;
            int[] exchanges = getShuffleExchanges(size, shuffleSeed);
            for (int i = size - 1; i > 1; i--) {
                int n = exchanges[size - 1 - i];
                Collections.swap(tracks, i, n + 1);
            }
        }

        state.clearTrack();
        state.addAllTrack(tracks);
    }

    public void unshuffleTracks() {
        if (shuffleSeed == 0 && !conf.defaultUnshuffleBehaviour() && state.hasContextUri()) {
            List<Remote3Track> tracks;
            try {
                MercuryRequests.ResolvedContextWrapper context = mercury.sendSync(MercuryRequests.resolveContext(state.getContextUri()));
                tracks = context.pages().get(0).tracks;
            } catch (IOException | MercuryClient.MercuryException ex) {
                LOGGER.fatal("Failed requesting context!", ex);
                return;
            }

            Spirc.TrackRef current = state.getTrack(state.getPlayingTrackIndex());

            List<Spirc.TrackRef> rebuildState = new ArrayList<>(80);
            TrackId currentTrackId = TrackId.fromTrackRef(current);
            String currentTrackUri = currentTrackId.toSpotifyUri();
            boolean add = false;
            int count = 80;
            for (Remote3Track track : tracks) {
                if (add || track.uri.equals(currentTrackUri)) {
                    track.addTo(rebuildState);

                    add = true;
                    count--;
                    if (count <= 0) break;
                }
            }

            state.clearTrack();
            state.addAllTrack(rebuildState);
            state.setPlayingTrackIndex(0);
        } else {
            List<Spirc.TrackRef> tracks = new ArrayList<>(state.getTrackList());
            if (state.getPlayingTrackIndex() != 0) {
                Collections.swap(tracks, 0, state.getPlayingTrackIndex());
                state.setPlayingTrackIndex(0);
            }

            int size = tracks.size() - 1;
            int[] exchanges = getShuffleExchanges(size, shuffleSeed);
            for (int i = 2; i < size; i++) {
                int n = exchanges[size - i - 1];
                Collections.swap(tracks, i, n + 1);
            }

            state.clearTrack();
            state.addAllTrack(tracks);
        }
    }

    @Override
    public int getNextTrackIndex(boolean consume) {
        int current = state.getPlayingTrackIndex();
        if (state.getTrack(current).getQueued()) {
            if (consume) {
                state.removeTrack(current);
                return current;
            }
        }

        return current + 1;
    }

    @Override
    public int getPrevTrackIndex() {
        return TracksProvider.getPrevTrackIndex(state);
    }

    @NotNull
    @Override
    public TrackId getCurrentTrack() {
        return TrackId.fromTrackRef(state.getTrack(state.getPlayingTrackIndex()));
    }

    @NotNull
    public TrackId getTrackAt(int index) throws IndexOutOfBoundsException {
        return TrackId.fromTrackRef(state.getTrack(index));
    }

    @Override
    public boolean canShuffle() {
        return true;
    }

    @Override
    public boolean canRepeat() {
        return true;
    }
}
