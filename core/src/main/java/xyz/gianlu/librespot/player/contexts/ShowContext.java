package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.player.tracks.PlayablesProvider;
import xyz.gianlu.librespot.player.tracks.ShowProvider;

/**
 * @author Gianlu
 */
public final class ShowContext extends AbsEpisodeContext {
    @Override
    public @NotNull PlayablesProvider initProvider(@NotNull Session session, Spirc.State.@NotNull Builder state) {
        return new ShowProvider(state);
    }
}
