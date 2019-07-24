package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.mercury.model.EpisodeId;

/**
 * @author Gianlu
 */
public final class ShowContext extends AbsSpotifyContext<EpisodeId> {

    public ShowContext(@NotNull String context) {
        super(context);
    }

    @Override
    public boolean isFinite() {
        return true;
    }
}
