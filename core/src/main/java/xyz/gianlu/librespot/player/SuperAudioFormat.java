package xyz.gianlu.librespot.player;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.proto.Metadata;

/**
 * @author Gianlu
 */
public enum SuperAudioFormat {
    MP3, VORBIS, AAC;

    @NotNull
    public static SuperAudioFormat get(@NotNull Metadata.AudioFile.Format format) {
        switch (format) {
            case OGG_VORBIS_96:
            case OGG_VORBIS_160:
            case OGG_VORBIS_320:
                return VORBIS;
            case MP3_256:
            case MP3_320:
            case MP3_160:
            case MP3_96:
            case MP3_160_ENC:
                return MP3;
            case AAC_24:
            case AAC_48:
                return AAC;
            default:
                throw new IllegalArgumentException("Unknown audio format: " + format);
        }
    }
}
