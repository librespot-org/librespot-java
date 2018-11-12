package org.librespot.spotify.player;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * @author Gianlu
 */
public interface AudioFile {
    void writeChunk(byte[] chunk, int chunkIndex) throws IOException;

    void header(byte id, byte[] bytes);

    void cacheFailed(int index, @NotNull AudioFile file);
}
