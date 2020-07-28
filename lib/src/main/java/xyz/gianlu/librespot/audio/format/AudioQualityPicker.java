package xyz.gianlu.librespot.audio.format;

import com.spotify.metadata.Metadata;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * @author Gianlu
 */
public interface AudioQualityPicker {

    @Nullable
    Metadata.AudioFile getFile(@NotNull List<Metadata.AudioFile> files);
}
