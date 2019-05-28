package xyz.gianlu.librespot.player.tracks;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.mercury.model.TrackId;
import xyz.gianlu.librespot.player.remote.Remote3Track;
import xyz.gianlu.librespot.spirc.SpotifyIrc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * @author Gianlu
 */
public class PlaylistProvider implements PlayablesProvider, ShuffleableProvider {
    private static final Logger LOGGER = Logger.getLogger(PlaylistProvider.class);
    private final Spirc.State.Builder state;
    private final MercuryClient mercury;
    private long shuffleSeed = 0;

    public PlaylistProvider(@NotNull Session session, @NotNull Spirc.State.Builder state) {
        this.state = state;
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

    @Override
    public void shuffleContent(@NotNull Random random, boolean fully) {
        if (state.getTrackCount() <= 1)
            return;

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
        SpotifyIrc.trimTracks(state);

        LOGGER.trace("Shuffled, seed: " + shuffleSeed);
    }

    @Override
    public void unshuffleContent() {
        if (state.getTrackCount() <= 1)
            return;

        if (shuffleSeed != 0) {
            List<Spirc.TrackRef> tracks = new ArrayList<>(state.getTrackList());
            if (state.getPlayingTrackIndex() != 0) {
                Collections.swap(tracks, 0, state.getPlayingTrackIndex());
                state.setPlayingTrackIndex(0);
            }

            int size = tracks.size() - 1;
            int[] exchanges = getShuffleExchanges(size, shuffleSeed);
            for (int i = 1; i < size; i++) {
                int n = exchanges[size - i - 1];
                Collections.swap(tracks, i, n + 1);
            }

            state.clearTrack();
            state.addAllTrack(tracks);

            LOGGER.trace("Unshuffled using seed: " + shuffleSeed);
            return;
        }

        if (state.hasContextUri()) {
            List<Remote3Track> tracks;
            try {
                MercuryRequests.ResolvedContextWrapper context = mercury.sendSync(MercuryRequests.resolveContext(state.getContextUri()));
                tracks = context.pages().get(0).tracks;
            } catch (IOException | MercuryClient.MercuryException ex) {
                LOGGER.fatal("Cannot unshuffle context!", ex);
                return;
            }

            PlayableId.removeUnsupported(tracks, -1);

            Spirc.TrackRef current = state.getTrack(state.getPlayingTrackIndex());
            String currentTrackUri = TrackId.fromTrackRef(current).toSpotifyUri();

            List<Spirc.TrackRef> rebuildState = new ArrayList<>(SpotifyIrc.MAX_TRACKS);
            boolean add = false;
            int count = SpotifyIrc.MAX_TRACKS;
            for (Remote3Track track : tracks) {
                if (add || track.uri.equals(currentTrackUri)) {
                    rebuildState.add(track.toTrackRef());

                    add = true;
                    count--;
                    if (count <= 0) break;
                }
            }

            if (rebuildState.isEmpty())
                throw new IllegalStateException("State cannot be empty!");

            state.clearTrack();
            state.addAllTrack(rebuildState);
            state.setPlayingTrackIndex(0);
            SpotifyIrc.trimTracks(state);

            LOGGER.trace("Unshuffled using context-resolve.");
        } else {
            LOGGER.fatal("Cannot unshuffle context! Did not know seed and context is missing.");
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
        return PlayablesProvider.getPrevTrackIndex(state);
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
