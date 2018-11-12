package org.librespot.spotify;

import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.player.Player;

import java.io.File;

/**
 * @author Gianlu
 */
public final class DefaultConfiguration extends AbsConfiguration {

    //****************//
    //---- PLAYER ----//
    //****************//

    @NotNull
    @Override
    public Player.AudioQuality preferredQuality() {
        return Player.AudioQuality.VORBIS_320;
    }

    @Override
    public float normalisationPregain() {
        return 0;
    }

    @Override
    public boolean pauseWhenLoading() {
        return false;
    }

    @Override
    public boolean preloadEnabled() {
        return true;
    }

    //****************//
    //---- CACHE -----//
    //****************//

    @Override
    public boolean cacheEnabled() {
        return true;
    }

    @Override
    public @NotNull File cacheDir() {
        return new File("./cache/");
    }
}
