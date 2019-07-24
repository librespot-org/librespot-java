package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.mercury.model.EpisodeId;

/**
 * @author Gianlu
 */
public final class EpisodeContext extends AbsSpotifyContext<EpisodeId> {

    public EpisodeContext(@NotNull String context) {
        super(context);
    }

    @Override
    public boolean isFinite() {
        return true;
    }
}
