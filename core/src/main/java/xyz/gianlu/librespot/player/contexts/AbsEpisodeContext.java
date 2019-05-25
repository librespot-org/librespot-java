package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.mercury.model.EpisodeId;

/**
 * @author Gianlu
 */
public abstract class AbsEpisodeContext implements SpotifyContext<EpisodeId> {

    @NotNull
    @Override
    public final EpisodeId createId(Spirc.@NotNull TrackRef ref) {
        return EpisodeId.fromTrackRef(ref);
    }
}
