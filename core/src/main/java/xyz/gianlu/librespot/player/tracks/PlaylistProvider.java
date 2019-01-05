package xyz.gianlu.librespot.player.tracks;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.mercury.model.TrackId;

import java.util.*;

/**
 * @author Gianlu
 */
public class PlaylistProvider implements TracksProvider {
    private final Spirc.State.Builder state;
    private long shuffleSeed = 0;

    public PlaylistProvider(@NotNull Spirc.State.Builder state, @NotNull Spirc.Frame frame) {
        this.state = state;

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

    @Override
    public int getNextTrackIndex(boolean consume) {
        int current = state.getPlayingTrackIndex();
        if (state.getTrack(current).getQueued()) {
            if (consume) state.removeTrack(current);
            return current;
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
