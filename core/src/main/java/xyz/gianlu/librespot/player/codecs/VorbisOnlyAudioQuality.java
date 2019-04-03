package xyz.gianlu.librespot.player.codecs;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.proto.Metadata;

/**
 * @author Gianlu
 */
public class VorbisOnlyAudioQuality implements AudioQualityPreference {
    private static final Logger LOGGER = Logger.getLogger(VorbisOnlyAudioQuality.class);
    private final AudioQuality preferred;

    public VorbisOnlyAudioQuality(@NotNull AudioQuality preferred) {
        this.preferred = preferred;
    }

    @Override
    public @Nullable Metadata.AudioFile getFile(Metadata.@NotNull Track track) {
        Metadata.AudioFile file = preferred.getFile(track);
        if (file == null) {
            file = AudioQuality.getAnyVorbisFile(track);
            if (file == null) {
                LOGGER.fatal(String.format("Couldn't find any Vorbis file, available: %s", AudioQuality.listFormats(track)));
                return null;
            } else {
                LOGGER.warn(String.format("Using %s because preferred %s couldn't be found.", file, preferred));
            }
        }

        return file;
    }
}
