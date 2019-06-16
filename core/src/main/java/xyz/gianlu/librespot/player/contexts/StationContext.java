package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.player.providers.ContentProvider;
import xyz.gianlu.librespot.player.providers.StationProvider;

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

    @Override
    public @NotNull ContentProvider initProvider(@NotNull Session session) {
        return new StationProvider(context, session);
    }
}
