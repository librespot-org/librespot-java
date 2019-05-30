package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.mercury.model.TrackId;

/**
 * @author Gianlu
 */
public abstract class AbsTrackContext extends AbsSpotifyContext<TrackId> {

    public AbsTrackContext(@NotNull String context) {
        super(context);
    }

    @NotNull
    @Override
    public final TrackId createId(Spirc.@NotNull TrackRef ref) {
        return TrackId.fromTrackRef(ref);
    }
}
