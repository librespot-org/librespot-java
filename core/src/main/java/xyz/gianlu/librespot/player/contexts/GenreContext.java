package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public final class GenreContext extends AbsTrackContext {

    public GenreContext(@NotNull String context) {
        super(context);
    }

    @Override
    public boolean isFinite() {
        return true;
    }
}
