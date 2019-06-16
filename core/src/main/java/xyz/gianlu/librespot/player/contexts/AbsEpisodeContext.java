package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.mercury.model.EpisodeId;

/**
 * @author Gianlu
 */
public abstract class AbsEpisodeContext extends AbsSpotifyContext<EpisodeId> {

    public AbsEpisodeContext(@NotNull String context) {
        super(context);
    }

    @NotNull
    @Override
    public final EpisodeId createId(Spirc.@NotNull TrackRef ref) {
        return EpisodeId.fromTrackRef(ref);
    }

    @Override
    public final EpisodeId createId(@NotNull String uri) {
        return EpisodeId.fromUri(uri);
    }
}
