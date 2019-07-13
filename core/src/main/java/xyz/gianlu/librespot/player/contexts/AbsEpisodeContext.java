package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.mercury.model.EpisodeId;

/**
 * @author Gianlu
 */
public abstract class AbsEpisodeContext extends AbsSpotifyContext<EpisodeId> {

    public AbsEpisodeContext(@NotNull String context) {
        super(context);
    }

    @Override
    public final EpisodeId createId(@NotNull String uri) {
        return EpisodeId.fromUri(uri);
    }
}
