package xyz.gianlu.librespot.player;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.mercury.model.TrackId;

/**
 * @author Gianlu
 */
public interface TracksProvider {

    int getNextTrackIndex(boolean consume);

    int getPrevTrackIndex(boolean consume);

    @NotNull
    TrackId getCurrentTrack();

    @NotNull
    TrackId getTrackAt(int index);

    boolean canShuffle();

    boolean canRepeat();
}
