package xyz.gianlu.librespot.player.tracks;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.mercury.model.EpisodeId;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * @author Gianlu
 */
public class ShowProvider implements TracksProvider {
    private final Spirc.State.Builder state;

    public ShowProvider(@NotNull Spirc.State.Builder state) {
        this.state = state;
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
    public int getPrevTrackIndex(boolean consume) { // TODO
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

    @Override
    public @NotNull EpisodeId getCurrentTrack() {
        return EpisodeId.fromTrackRef(state.getTrack(state.getPlayingTrackIndex()));
    }

    @Override
    public @NotNull EpisodeId getTrackAt(int index) {
        return EpisodeId.fromTrackRef(state.getTrack(index));
    }

    @Override
    public boolean canShuffle() {
        return false;
    }

    @Override
    public boolean canRepeat() {
        return false;
    }
}
