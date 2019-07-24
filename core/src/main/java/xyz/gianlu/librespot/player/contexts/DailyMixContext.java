package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.mercury.model.TrackId;

/**
 * @author Gianlu
 */
public final class DailyMixContext extends AbsSpotifyContext<TrackId> {

    public DailyMixContext(@NotNull String context) {
        super(context);
    }

    @Override
    public boolean isFinite() {
        return false;
    }
}
