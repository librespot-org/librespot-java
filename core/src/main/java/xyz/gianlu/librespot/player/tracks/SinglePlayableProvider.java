package xyz.gianlu.librespot.player.tracks;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.mercury.model.PlayableId;

/**
 * @author Gianlu
 */
public class SinglePlayableProvider implements PlayablesProvider {
    private final PlayableId id;

    public SinglePlayableProvider(@NotNull PlayableId id) {
        this.id = id;
    }

    @Override
    public int getNextTrackIndex(boolean consume) {
        return 1;
    }

    @Override
    public int getPrevTrackIndex() {
        return 0;
    }

    @Override
    public @NotNull PlayableId getCurrentTrack() {
        return id;
    }

    @Override
    public @NotNull PlayableId getTrackAt(int index) {
        if (index != 0) throw new IndexOutOfBoundsException();
        return id;
    }

    @Override
    public boolean canShuffle() {
        return false;
    }

    @Override
    public boolean canRepeat() {
        return true;
    }
}
