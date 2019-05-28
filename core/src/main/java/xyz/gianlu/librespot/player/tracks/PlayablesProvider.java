package xyz.gianlu.librespot.player.tracks;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.mercury.model.PlayableId;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * @author Gianlu
 */
public interface PlayablesProvider {

    static int getPrevTrackIndex(@NotNull Spirc.State.Builder state) {
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

    int getNextTrackIndex(boolean consume);

    int getPrevTrackIndex();

    @NotNull
    PlayableId getCurrentTrack();

    @NotNull
    PlayableId getTrackAt(int index) throws IndexOutOfBoundsException;

    boolean canShuffle();

    boolean canRepeat();
}
