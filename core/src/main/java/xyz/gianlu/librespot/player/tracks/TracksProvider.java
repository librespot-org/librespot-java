package xyz.gianlu.librespot.player.tracks;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.mercury.model.PlayableId;

/**
 * @author Gianlu
 */
public interface TracksProvider {

    int getNextTrackIndex(boolean consume);

    int getPrevTrackIndex(boolean consume);

    @NotNull
    PlayableId getCurrentTrack();

    @NotNull
    PlayableId getTrackAt(int index);

    boolean canShuffle();

    boolean canRepeat();
}
