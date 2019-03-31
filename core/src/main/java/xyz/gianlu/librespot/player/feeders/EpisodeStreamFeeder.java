package xyz.gianlu.librespot.player.feeders;


import javafx.fxml.LoadException;
import okhttp3.*;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.proto.Metadata;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.model.EpisodeId;
import xyz.gianlu.librespot.player.AbsChunckedInputStream;
import xyz.gianlu.librespot.player.GeneralAudioStream;
import xyz.gianlu.librespot.player.GeneralWritableStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static xyz.gianlu.librespot.player.ChannelManager.CHUNK_SIZE;

/**
 * @author Gianlu
 */
public class EpisodeStreamFeeder {
    private static final Logger LOGGER = Logger.getLogger(EpisodeStreamFeeder.class);
    private final Session session;

    public EpisodeStreamFeeder(@NotNull Session session) {
        this.session = session;
    }

    private static byte[] readBytes(@NotNull Response response) throws IOException {
        ResponseBody body = response.body();
        if (body == null) throw new IOException("Empty body!");
        return body.bytes();
    }

    @NotNull
    public LoadedStream load(@NotNull EpisodeId id, boolean cdn) throws IOException, MercuryClient.MercuryException {
        if (!cdn) throw new UnsupportedOperationException("Episodes are support only through CDN!" /* TODO */);

        Metadata.Episode resp = session.mercury().sendSync(MercuryRequests.getEpisode(id)).proto();

        String externalUrl = resp.getExternalUrl();
        if (externalUrl == null)
            throw new IllegalArgumentException("Missing external_url!");

        return new LoadedStream(resp, new EpisodeStream(id.hexId(), externalUrl));
    }

    private static class Loader {
        private final OkHttpClient client;
        private final HttpUrl url;

        Loader(@NotNull String url) throws IOException {
            this.client = new OkHttpClient();

            Response resp = client.newCall(new Request.Builder().head()
                    .url(url).build()).execute();

            if (resp.code() != 200)
                LOGGER.warn("Couldn't resolve redirect!");

            this.url = resp.request().url();
        }

        @NotNull
        public synchronized Response request(int chunk) throws IOException {
            return request(CHUNK_SIZE * chunk, (chunk + 1) * CHUNK_SIZE - 1);
        }

        @NotNull
        public synchronized Response request(int rangeStart, int rangeEnd) throws IOException {
            return client.newCall(new Request.Builder().get()
                    .addHeader("Range", "bytes=" + rangeStart + "-" + rangeEnd)
                    .url(url).build()).execute();
        }
    }

    public static class EpisodeStream implements GeneralAudioStream, GeneralWritableStream {
        private final String fileId;
        private final ExecutorService executorService = Executors.newCachedThreadPool();
        private final int size;
        private final byte[][] buffer;
        private final boolean[] available;
        private final boolean[] requested;
        private final int chunks;
        private final InternalStream internalStream;
        private final Loader loader;

        EpisodeStream(@NotNull String fileId, @NotNull String url) throws IOException {
            this.fileId = fileId;
            this.loader = new Loader(url);

            byte[] firstChunk;
            Response resp = loader.request(0, CHUNK_SIZE - 1);
            String contentRange = resp.header("Content-Range");
            if (contentRange == null)
                throw new LoadException("Missing Content-Range header!");

            String[] split = Utils.split(contentRange, '/');
            size = Integer.parseInt(split[1]);
            chunks = (int) Math.ceil((float) size / (float) CHUNK_SIZE);

            firstChunk = readBytes(resp);

            available = new boolean[chunks];
            requested = new boolean[chunks];

            buffer = new byte[chunks][CHUNK_SIZE];
            buffer[chunks - 1] = new byte[size % CHUNK_SIZE];

            this.internalStream = new InternalStream();
            writeChunk(firstChunk, 0, false);
        }

        @Override
        public @NotNull InputStream stream() {
            return internalStream;
        }

        @Override
        public @NotNull String getFileIdHex() {
            return fileId;
        }

        @Override
        public @NotNull Codec codec() {
            return Codec.STANDARD;
        }

        @Override
        public void writeChunk(@NotNull byte[] chunk, int chunkIndex, boolean cached) throws IOException {
            if (internalStream.isClosed()) return;

            if (chunk.length != buffer[chunkIndex].length)
                throw new IOException(String.format("Invalid buffer length, required: %d, provided: %d", buffer[chunkIndex].length, chunk.length));

            LOGGER.trace(String.format("Chunk %d/%d completed, host: %s, cached: %b, fileId: %s", chunkIndex, chunks, loader.url, cached, fileId));

            buffer[chunkIndex] = chunk;

            internalStream.notifyChunkAvailable(chunkIndex);
        }

        private void requestChunk(int index) {
            try {
                Response resp = loader.request(index);
                writeChunk(readBytes(resp), index, false);
            } catch (IOException ex) {
                LOGGER.fatal(String.format("Failed requesting chunk, index: %d", index), ex);
            }
        }

        private class InternalStream extends AbsChunckedInputStream {

            @Override
            protected byte[][] buffer() {
                return buffer;
            }

            @Override
            protected int size() {
                return size;
            }

            @Override
            protected boolean[] requestedChunks() {
                return requested;
            }

            @Override
            protected boolean[] availableChunks() {
                return available;
            }

            @Override
            protected int chunks() {
                return chunks;
            }

            @Override
            protected void requestChunkFromStream(int index) {
                executorService.execute(() -> requestChunk(index));
            }
        }
    }

    public static class LoadedStream {
        public final Metadata.Episode episode;
        public final GeneralAudioStream in;

        LoadedStream(@NotNull Metadata.Episode episode, @NotNull GeneralAudioStream in) {
            this.episode = episode;
            this.in = in;
        }
    }
}
