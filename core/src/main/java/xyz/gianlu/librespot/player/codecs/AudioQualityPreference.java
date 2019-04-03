package xyz.gianlu.librespot.player.codecs;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.proto.Metadata;

/**
 * @author Gianlu
 */
public interface AudioQualityPreference {

    @Nullable
    Metadata.AudioFile getFile(@NotNull Metadata.Track track);
}
