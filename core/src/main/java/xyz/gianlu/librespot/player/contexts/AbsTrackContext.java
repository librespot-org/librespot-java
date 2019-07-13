package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.mercury.model.TrackId;

/**
 * @author Gianlu
 */
public abstract class AbsTrackContext extends AbsSpotifyContext<TrackId> {

    public AbsTrackContext(@NotNull String context) {
        super(context);
    }

    @Override
    public final TrackId createId(@NotNull String uri) {
        return TrackId.fromUri(uri);
    }
}
