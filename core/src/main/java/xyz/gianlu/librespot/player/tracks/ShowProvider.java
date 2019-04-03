package xyz.gianlu.librespot.player.tracks;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.mercury.model.EpisodeId;

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
    public int getPrevTrackIndex() {
        return TracksProvider.getPrevTrackIndex(state);
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
