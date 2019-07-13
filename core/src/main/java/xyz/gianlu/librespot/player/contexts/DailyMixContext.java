package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public final class DailyMixContext extends AbsTrackContext {

    public DailyMixContext(@NotNull String context) {
        super(context);
    }

    @Override
    public boolean isFinite() {
        return false;
    }
}
