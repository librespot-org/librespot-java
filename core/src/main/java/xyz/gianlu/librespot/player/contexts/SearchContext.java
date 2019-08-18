package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public final class SearchContext extends GeneralFiniteContext {
    public final String searchTerm;

    public SearchContext(@NotNull String context, @NotNull String searchTerm) {
        super(context);
        this.searchTerm = searchTerm;
    }
}
