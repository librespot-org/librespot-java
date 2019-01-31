package xyz.gianlu.librespot.player.tracks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.model.TrackId;
import xyz.gianlu.librespot.player.Player;

import java.io.IOException;
import java.util.*;

/**
 * @author Gianlu
 */
public class PlaylistProvider implements TracksProvider {
    private static final Logger LOGGER = Logger.getLogger(PlaylistProvider.class);
    private final Spirc.State.Builder state;
    private final Player.Configuration conf;
    private final MercuryClient mercury;
    private long shuffleSeed = 0;

    public PlaylistProvider(@NotNull Session session, @NotNull Spirc.State.Builder state, @NotNull Spirc.Frame frame, @NotNull Player.Configuration conf) {
        this.state = state;
        this.conf = conf;
        this.mercury = session.mercury();

        state.setPlayingTrackIndex(frame.getState().getPlayingTrackIndex());
        state.clearTrack();
        state.addAllTrack(frame.getState().getTrackList());
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

    public void shuffleTracks(@NotNull Random random) {
        shuffleSeed = random.nextLong();

        List<Spirc.TrackRef> tracks = new ArrayList<>(state.getTrackList());
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

        state.clearTrack();
        state.addAllTrack(tracks);
    }

    public void unshuffleTracks() {
        if (shuffleSeed == 0 && !conf.defaultUnshuffleBehaviour() && state.hasContextUri()) {
            JsonArray tracks;
            try {
                MercuryRequests.ResolvedContextWrapper context = mercury.sendSync(MercuryRequests.resolveContext(state.getContextUri()));
                tracks = context.pages().get(0).getAsJsonObject().getAsJsonArray("tracks");
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
            for (JsonElement elm : tracks) {
                JsonObject track = elm.getAsJsonObject();
                String uri = track.get("uri").getAsString();
                if (add || uri.equals(currentTrackUri)) {
                    rebuildState.add(Spirc.TrackRef.newBuilder()
                            .setUri(uri)
                            .setGid(ByteString.copyFrom(TrackId.fromUri(uri).getGid()))
                            .build());

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
    public int getPrevTrackIndex(boolean consume) {
        List<Spirc.TrackRef> queueTracks = new ArrayList<>();
        Iterator<Spirc.TrackRef> iter = state.getTrackList().iterator();
        while (iter.hasNext()) {
            Spirc.TrackRef track = iter.next();
            if (track.getQueued()) {
                queueTracks.add(track);
                iter.remove();
            }
        }

        int current = state.getPlayingTrackIndex();
        int newIndex;
        if (current > 0) newIndex = current - 1;
        else if (state.getRepeat()) newIndex = state.getTrackCount() - 1;
        else newIndex = 0;

        for (int i = 0; i < queueTracks.size(); i++)
            state.getTrackList().add(newIndex + 1 + i, queueTracks.get(i));

        return newIndex;
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
