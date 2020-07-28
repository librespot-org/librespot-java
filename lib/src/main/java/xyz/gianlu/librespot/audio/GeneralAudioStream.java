package xyz.gianlu.librespot.audio;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.audio.format.SuperAudioFormat;


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

    int decryptTimeMs();
}
