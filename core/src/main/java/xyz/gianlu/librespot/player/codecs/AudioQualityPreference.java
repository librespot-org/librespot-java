package xyz.gianlu.librespot.player.codecs;

import com.spotify.metadata.proto.Metadata;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Gianlu
 */
public interface AudioQualityPreference {

    @Nullable
    Metadata.AudioFile getFile(@NotNull Metadata.Track track);
}
