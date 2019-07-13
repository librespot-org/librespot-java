package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public final class StationContext extends AbsTrackContext {

    public StationContext(@NotNull String context) {
        super(context);
    }

    @Override
    public boolean isFinite() {
        return false;
    }
}
