package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.mercury.model.TrackId;

/**
 * @author Gianlu
 */
public abstract class AbsTrackContext implements SpotifyContext<TrackId> {

    @NotNull
    @Override
    public final TrackId createId(Spirc.@NotNull TrackRef ref) {
        return TrackId.fromTrackRef(ref);
    }
}
