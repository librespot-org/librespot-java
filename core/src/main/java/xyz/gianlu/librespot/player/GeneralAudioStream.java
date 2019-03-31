package xyz.gianlu.librespot.player;

import org.jetbrains.annotations.NotNull;

import java.io.InputStream;

/**
 * @author Gianlu
 */
public interface GeneralAudioStream {
    @NotNull
    InputStream stream();

    @NotNull
    String getFileIdHex();

    @NotNull
    Codec codec();

    enum Codec {
        VORBIS,
        STANDARD
    }
}
