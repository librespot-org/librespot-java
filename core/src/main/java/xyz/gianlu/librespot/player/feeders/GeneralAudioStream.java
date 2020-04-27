package xyz.gianlu.librespot.player.feeders;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.player.codecs.SuperAudioFormat;

/**
 * @author Gianlu
 */
public interface GeneralAudioStream {
    @NotNull
    AbsChunkedInputStream stream();

    @NotNull
    SuperAudioFormat codec();

    @NotNull
    String describe();
}
