package org.librespot.spotify.player;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.core.Session;
import org.librespot.spotify.proto.Metadata;

import java.io.IOException;
import java.io.InputStream;

import static org.librespot.spotify.player.ChannelManager.CHUNK_SIZE;

/**
 * @author Gianlu
 */
public class AudioFileStreaming implements AudioFile {
    private static final Logger LOGGER = Logger.getLogger(AudioFileStreaming.class);
    private final ByteString fileId;
    private final byte[] key;
    private final Session session;
    private int chunks = -1;
    private ChunksBuffer chunksBuffer;

    public AudioFileStreaming(@NotNull Session session, @NotNull Metadata.AudioFile file, byte[] key) {
        this.session = session;
        this.fileId = file.getFileId();
        this.key = key;
    }

    @NotNull
    public InputStream stream() {
        if (chunksBuffer == null) throw new IllegalStateException("Stream not open!");
        return chunksBuffer.stream();
    }

    public void open() throws IOException {
        AudioFileFetch fetch = new AudioFileFetch();
        session.channel().requestChunk(fileId, 0, fetch);

        fetch.waitChunk();

        int size = fetch.getSize();
        LOGGER.trace("Track size: " + size);
        chunks = (size + CHUNK_SIZE - 1) / CHUNK_SIZE;
        LOGGER.trace(String.format("Track has %d chunks.", chunks));

        chunksBuffer = new ChunksBuffer(size, chunks, key);

        for (int i = 0; i < 10; i++) {
            session.channel().requestChunk(fileId, i, this);
        }
    }

    @Override
    public void writeChunk(byte[] buffer, int chunkIndex) throws IOException {
        chunksBuffer.writeChunk(buffer, chunkIndex);
        LOGGER.trace(String.format("Chunk %d/%d completed.", chunkIndex, chunks));
    }

    @Override
    public void header(byte id, byte[] bytes) {
    }
}
