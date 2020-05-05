package xyz.gianlu.librespot.player.codecs;

import com.spotify.metadata.Metadata.AudioFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Gianlu
 */
public enum AudioQuality {
    NORMAL, HIGH, VERY_HIGH;

    @NotNull
    private static AudioQuality getQuality(@NotNull AudioFile.Format format) {
        switch (format) {
            case MP3_96:
            case OGG_VORBIS_96:
            case AAC_24_NORM:
                return NORMAL;
            case MP3_160:
            case MP3_160_ENC:
            case OGG_VORBIS_160:
            case AAC_24:
                return HIGH;
            case MP3_320:
            case MP3_256:
            case OGG_VORBIS_320:
            case AAC_48:
                return VERY_HIGH;
            default:
                throw new IllegalArgumentException("Unknown format: " + format);
        }
    }

    public @NotNull List<AudioFile> getMatches(@NotNull List<AudioFile> files) {
        List<AudioFile> list = new ArrayList<>(files.size());
        for (AudioFile file : files) {
            if (file.hasFormat() && getQuality(file.getFormat()) == this)
                list.add(file);
        }

        return list;
    }
}
