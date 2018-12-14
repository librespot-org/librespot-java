package xyz.gianlu.librespot.player;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * @author Gianlu
 */
public interface AudioFile {
    void writeChunk(byte[] chunk, int chunkIndex, boolean cached) throws IOException;

    void cacheFailedChunk(int index, @NotNull AudioFile file);

    void writeHeader(byte id, byte[] bytes, boolean cached);

    void cacheFailedHeader(@NotNull AudioFile file);

    void headerEnd(boolean cached);

    void streamError(short code);
}
