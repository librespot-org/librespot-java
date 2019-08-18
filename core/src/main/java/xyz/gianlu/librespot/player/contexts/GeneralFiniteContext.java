package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;

public class GeneralFiniteContext extends AbsSpotifyContext {
    GeneralFiniteContext(@NotNull String context) {
        super(context);
    }

    @Override
    public final boolean isFinite() {
        return true;
    }
}
