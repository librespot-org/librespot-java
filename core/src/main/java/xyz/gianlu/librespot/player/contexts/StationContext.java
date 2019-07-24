package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.mercury.model.TrackId;

/**
 * @author Gianlu
 */
public final class StationContext extends AbsSpotifyContext<TrackId> {

    public StationContext(@NotNull String context) {
        super(context);
    }

    @Override
    public boolean isFinite() {
        return false;
    }
}
