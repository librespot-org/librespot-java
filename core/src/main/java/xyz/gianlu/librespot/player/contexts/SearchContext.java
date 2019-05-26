package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.player.tracks.PlayablesProvider;
import xyz.gianlu.librespot.player.tracks.PlaylistProvider;

/**
 * @author Gianlu
 */
public final class SearchContext extends AbsTrackContext {
    public final String searchTerm;

    public SearchContext(@NotNull String searchTerm) {
        this.searchTerm = searchTerm;
    }

    @Override
    public @NotNull PlayablesProvider initProvider(@NotNull Session session, Spirc.State.@NotNull Builder state) {
        return new PlaylistProvider(session, state);
    }
}
