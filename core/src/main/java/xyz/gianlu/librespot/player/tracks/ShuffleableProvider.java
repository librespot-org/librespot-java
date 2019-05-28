package xyz.gianlu.librespot.player.tracks;

import org.jetbrains.annotations.NotNull;

import java.util.Random;

/**
 * @author Gianlu
 */
public interface ShuffleableProvider {
    void shuffleContent(@NotNull Random random, boolean fully);

    void unshuffleContent();
}
