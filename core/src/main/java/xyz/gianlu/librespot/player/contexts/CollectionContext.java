package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public final class CollectionContext extends AbsTrackContext {

    public CollectionContext(@NotNull String context) {
        super(context);
    }

    @Override
    public boolean isFinite() {
        return true;
    }
}
