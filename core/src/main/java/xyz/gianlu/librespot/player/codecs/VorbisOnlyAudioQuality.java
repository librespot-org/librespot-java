package xyz.gianlu.librespot.player.codecs;

import com.spotify.metadata.Metadata;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * @author Gianlu
 */
public class VorbisOnlyAudioQuality implements AudioQualityPreference {
    private static final Logger LOGGER = LogManager.getLogger(VorbisOnlyAudioQuality.class);
    private final AudioQuality preferred;

    public VorbisOnlyAudioQuality(@NotNull AudioQuality preferred) {
        this.preferred = preferred;
    }

    @Override
    public @Nullable Metadata.AudioFile getFile(@NotNull List<Metadata.AudioFile> files) {
        Metadata.AudioFile file = preferred.getFile(files);
        if (file == null) {
            file = AudioQuality.getAnyVorbisFile(files);
            if (file == null) {
                LOGGER.fatal("Couldn't find any Vorbis file, available: {}", AudioQuality.listFormats(files));
                return null;
            } else {
                LOGGER.warn("Using {} because preferred {} couldn't be found.", file, preferred);
            }
        }

        return file;
    }
}
