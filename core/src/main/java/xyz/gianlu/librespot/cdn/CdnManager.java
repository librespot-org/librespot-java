package xyz.gianlu.librespot.cdn;

import com.google.protobuf.ByteString;
import okhttp3.*;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.cache.CacheManager;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.proto.Metadata;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.player.*;
import xyz.gianlu.librespot.player.codecs.SuperAudioFormat;
import xyz.gianlu.librespot.player.decrypt.AesAudioDecrypt;
import xyz.gianlu.librespot.player.decrypt.AudioDecrypt;
import xyz.gianlu.librespot.player.decrypt.NoopAudioDecrypt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static xyz.gianlu.librespot.player.ChannelManager.CHUNK_SIZE;

/**
 * @author Gianlu
 */
public class CdnManager {
    private static final String STORAGE_RESOLVE_AUDIO_URL = "https://spclient.wg.spotify.com/storage-resolve/files/audio/interactive/%s";
    private static final Logger LOGGER = Logger.getLogger(CdnManager.class);
    private final Session session;
    private final OkHttpClient client;

    public CdnManager(@NotNull Session session) {
        this.session = session;
        this.client = new OkHttpClient();
    }

    @NotNull
    public OkHttpClient client() {
        return client;
    }

    @NotNull
    private InputStream getHead(@NotNull ByteString fileId) throws IOException {
        Response resp = client.newCall(new Request.Builder()
                .get().url("https://heads-fa.spotify.com/head/" + Utils.bytesToHex(fileId).toLowerCase())
                .build()).execute();

        if (resp.code() != 200)
            throw new IOException(resp.code() + ": " + resp.message());

        ResponseBody body = resp.body();
        if (body == null)
            throw new IOException("Response body is empty!");

        return body.byteStream();
    }

    @NotNull
    public Streamer streamEpisode(@NotNull Metadata.Episode episode, @NotNull HttpUrl externalUrl) throws IOException {
        return new Streamer(new StreamId(episode), SuperAudioFormat.MP3, externalUrl, session.cache(), new NoopAudioDecrypt());
    }

    @NotNull
    public Streamer streamTrack(@NotNull Metadata.AudioFile file, @NotNull byte[] key) throws IOException, MercuryClient.MercuryException, CdnException {
        return new Streamer(new StreamId(file), SuperAudioFormat.get(file.getFormat()),
                getAudioUrl(file.getFileId()), session.cache(), new AesAudioDecrypt(key));
    }

    @NotNull
    private HttpUrl getAudioUrl(@NotNull ByteString fileId) throws IOException, MercuryClient.MercuryException, CdnException {
        try (Response resp = client.newCall(new Request.Builder().get()
                .header("Authorization", "Bearer " + session.tokens().get("playlist-read"))
                .url(String.format(STORAGE_RESOLVE_AUDIO_URL, Utils.bytesToHex(fileId)))
                .build()).execute()) {

            if (resp.code() != 200)
                throw new IOException(resp.code() + ": " + resp.message());

            ResponseBody body = resp.body();
            if (body == null)
                throw new IOException("Response body is empty!");

            StorageResolve.StorageResolveResponse proto = StorageResolve.StorageResolveResponse.parseFrom(body.byteStream());
            if (proto.getResult() == StorageResolve.StorageResolveResponse.Result.CDN) {
                return HttpUrl.get(proto.getCdnurl(session.random().nextInt(proto.getCdnurlCount())));
            } else {
                throw new CdnException(String.format("Could not retrieve CDN url! {result: %s}", proto.getResult()));
            }
        }
    }

    public static class CdnException extends Exception {

        CdnException(@NotNull String message) {
            super(message);
        }
    }

    private static class InternalResponse {
        private final byte[] buffer;
        private final Headers headers;

        InternalResponse(byte[] buffer, Headers headers) {
            this.buffer = buffer;
            this.headers = headers;
        }
    }

    public class Streamer implements GeneralAudioStream, GeneralWritableStream {
        private final StreamId streamId;
        private final ExecutorService executorService = Executors.newCachedThreadPool();
        private final SuperAudioFormat format;
        private final AudioDecrypt audioDecrypt;
        private final HttpUrl cdnUrl;
        private final int size;
        private final byte[][] buffer;
        private final boolean[] available;
        private final boolean[] requested;
        private final int chunks;
        private final InternalStream internalStream;
        private final CacheManager.Handler cacheHandler;

        private Streamer(@NotNull StreamId streamId, @NotNull SuperAudioFormat format, @NotNull HttpUrl cdnUrl, @Nullable CacheManager cache, @Nullable AudioDecrypt audioDecrypt) throws IOException {
            this.streamId = streamId;
            this.format = format;
            this.audioDecrypt = audioDecrypt;
            this.cdnUrl = cdnUrl;
            this.cacheHandler = cache != null ? cache.forWhatever(streamId) : null;

            byte[] firstChunk;
            try {
                byte[] sizeHeader;
                if (cacheHandler == null || (sizeHeader = cacheHandler.getHeader(AudioFileFetch.HEADER_SIZE)) == null) {
                    InternalResponse resp = request(0, CHUNK_SIZE - 1);
                    String contentRange = resp.headers.get("Content-Range");
                    if (contentRange == null)
                        throw new IOException("Missing Content-Range header!");

                    String[] split = Utils.split(contentRange, '/');
                    size = Integer.parseInt(split[1]);
                    chunks = (int) Math.ceil((float) size / (float) CHUNK_SIZE);

                    firstChunk = resp.buffer;

                    if (cacheHandler != null)
                        cacheHandler.setHeader(AudioFileFetch.HEADER_SIZE, ByteBuffer.allocate(4).putInt(size / 4).array());
                } else {
                    size = ByteBuffer.wrap(sizeHeader).getInt() * 4;
                    chunks = (size + CHUNK_SIZE - 1) / CHUNK_SIZE;

                    firstChunk = cacheHandler.readChunk(0);
                }
            } catch (SQLException ex) {
                throw new IOException(ex);
            }

            available = new boolean[chunks];
            requested = new boolean[chunks];

            buffer = new byte[chunks][CHUNK_SIZE];
            buffer[chunks - 1] = new byte[size % CHUNK_SIZE];

            this.internalStream = new InternalStream();
            writeChunk(firstChunk, 0, false);
        }

        @Override
        public void writeChunk(@NotNull byte[] chunk, int chunkIndex, boolean cached) throws IOException {
            if (internalStream.isClosed()) return;

            if (!cached && cacheHandler != null) {
                try {
                    cacheHandler.writeChunk(chunk, chunkIndex);
                } catch (SQLException ex) {
                    LOGGER.warn(String.format("Failed writing to cache! {index: %d}", chunkIndex), ex);
                }
            }

            LOGGER.trace(String.format("Chunk %d/%d completed, cdn: %s, cached: %b, stream: %s", chunkIndex, chunks, cdnUrl.host(), cached, describe()));

            audioDecrypt.decryptChunk(chunkIndex, chunk, buffer[chunkIndex]);
            internalStream.notifyChunkAvailable(chunkIndex);
        }

        @Override
        public @NotNull InputStream stream() {
            return internalStream;
        }

        @Override
        public @NotNull SuperAudioFormat codec() {
            return format;
        }

        @Override
        public @NotNull String describe() {
            if (streamId.isEpisode()) return "{episodeGid: " + streamId.getEpisodeGid() + "}";
            else return "{fileId: " + streamId.getFileId() + "}";
        }

        private void requestChunk(int index) {
            if (cacheHandler != null) {
                try {
                    if (cacheHandler.hasChunk(index)) {
                        cacheHandler.readChunk(index, this);
                        return;
                    }
                } catch (SQLException | IOException ex) {
                    LOGGER.fatal(String.format("Failed requesting chunk from cache, index: %d", index), ex);
                }
            }

            try {
                InternalResponse resp = request(index);
                writeChunk(resp.buffer, index, false);
            } catch (IOException ex) {
                LOGGER.fatal(String.format("Failed requesting chunk from network, index: %d", index), ex);
            }
        }

        @NotNull
        public synchronized InternalResponse request(int chunk) throws IOException {
            return request(CHUNK_SIZE * chunk, (chunk + 1) * CHUNK_SIZE - 1);
        }

        @NotNull
        public synchronized InternalResponse request(int rangeStart, int rangeEnd) throws IOException {
            try (Response resp = client.newCall(new Request.Builder().get().url(cdnUrl)
                    .header("Range", "bytes=" + rangeStart + "-" + rangeEnd)
                    .build()).execute()) {

                if (resp.code() != 206)
                    throw new IOException(resp.code() + ": " + resp.message());

                ResponseBody body = resp.body();
                if (body == null)
                    throw new IOException("Response body is empty!");

                return new InternalResponse(body.bytes(), resp.headers());
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
}
