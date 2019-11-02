package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;

public class GeneralInfiniteContext extends AbsSpotifyContext {
    GeneralInfiniteContext(@NotNull String context) {
        super(context);
    }

    @Override
    public final boolean isFinite() {
        return false;
    }
}
