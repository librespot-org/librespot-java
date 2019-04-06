package xyz.gianlu.librespot.player;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.player.codecs.SuperAudioFormat;

import java.io.InputStream;

/**
 * @author Gianlu
 */
public interface GeneralAudioStream {
    @NotNull
    InputStream stream();

    @NotNull
    SuperAudioFormat codec();

    @NotNull
    String describe();
}
