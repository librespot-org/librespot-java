package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.mercury.model.TrackId;

/**
 * @author Gianlu
 */
public final class SearchContext extends AbsSpotifyContext<TrackId> {
    public final String searchTerm;

    public SearchContext(@NotNull String context, @NotNull String searchTerm) {
        super(context);
        this.searchTerm = searchTerm;
    }

    @Override
    public boolean isFinite() {
        return true;
    }
}
