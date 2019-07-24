package xyz.gianlu.librespot.player.codecs;

import com.spotify.metadata.proto.Metadata;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * @author Gianlu
 */
public interface AudioQualityPreference {

    @Nullable
    Metadata.AudioFile getFile(@NotNull List<Metadata.AudioFile> files);
}
