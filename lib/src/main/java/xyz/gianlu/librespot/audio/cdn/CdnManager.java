package xyz.gianlu.librespot.audio.cdn;

import com.google.protobuf.ByteString;
import com.spotify.metadata.Metadata;
import com.spotify.storage.StorageResolve.StorageResolveResponse;
import okhttp3.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.audio.*;
import xyz.gianlu.librespot.audio.decrypt.AesAudioDecrypt;
import xyz.gianlu.librespot.audio.decrypt.AudioDecrypt;
import xyz.gianlu.librespot.audio.decrypt.NoopAudioDecrypt;
import xyz.gianlu.librespot.audio.format.SuperAudioFormat;
import xyz.gianlu.librespot.audio.storage.AudioFileFetch;
import xyz.gianlu.librespot.cache.CacheManager;
import xyz.gianlu.librespot.common.NameThreadFactory;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static xyz.gianlu.librespot.audio.storage.ChannelManager.CHUNK_SIZE;

/**
 * @author Gianlu
 */
public class CdnManager {
    private static final Logger LOGGER = LogManager.getLogger(CdnManager.class);
    private final Session session;

    public CdnManager(@NotNull Session session) {
        this.session = session;
    }

    @NotNull
    private InputStream getHead(@NotNull ByteString fileId) throws IOException {
        Response resp = session.client().newCall(new Request.Builder()
                .get().url(session.getUserAttribute("head-files-url", "https://heads-fa.spotify.com/head/{file_id}").replace("{file_id}", Utils.bytesToHex(fileId).toLowerCase()))
                .build()).execute();

        if (resp.code() != 200)
            throw new IOException(resp.code() + ": " + resp.message());

        ResponseBody body = resp.body();
        if (body == null)
            throw new IOException("Response body is empty!");

        return body.byteStream();
    }

    @NotNull
    public Streamer streamExternalEpisode(@NotNull Metadata.Episode episode, @NotNull HttpUrl externalUrl, @Nullable HaltListener haltListener) throws IOException, CdnException {
        return new Streamer(new StreamId(episode), SuperAudioFormat.MP3 /* Guaranteed */, new CdnUrl(null, externalUrl),
                session.cache(), new NoopAudioDecrypt(), haltListener);
    }

    @NotNull
    public Streamer streamFile(@NotNull Metadata.AudioFile file, @NotNull byte[] key, @NotNull HttpUrl url, @Nullable HaltListener haltListener) throws IOException, CdnException {
        return new Streamer(new StreamId(file), SuperAudioFormat.get(file.getFormat()), new CdnUrl(file.getFileId(), url),
                session.cache(), new AesAudioDecrypt(key), haltListener);
    }

    /**
     * This is used only to RENEW the url if needed.
     */
    @NotNull
    private HttpUrl getAudioUrl(@NotNull ByteString fileId) throws IOException, CdnException, MercuryClient.MercuryException {
        try (Response resp = session.api().send("GET", String.format("/storage-resolve/files/audio/interactive/%s", Utils.bytesToHex(fileId)), null, null)) {
            if (resp.code() != 200)
                throw new IOException(resp.code() + ": " + resp.message());

            ResponseBody body = resp.body();
            if (body == null) throw new IOException("Response body is empty!");

            StorageResolveResponse proto = StorageResolveResponse.parseFrom(body.byteStream());
            if (proto.getResult() == StorageResolveResponse.Result.CDN) {
                String url = proto.getCdnurl(session.random().nextInt(proto.getCdnurlCount()));
                LOGGER.debug("Fetched CDN url for {}: {}", Utils.bytesToHex(fileId), url);
                return HttpUrl.get(url);
            } else {
                throw new CdnException(String.format("Could not retrieve CDN url! {result: %s}", proto.getResult()));
            }
        }
    }

    public static class CdnException extends Exception {

        CdnException(@NotNull String message) {
            super(message);
        }

        CdnException(@NotNull Throwable ex) {
            super(ex);
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

    private class CdnUrl {
        private final ByteString fileId;
        private long expiration;
        private HttpUrl url;

        CdnUrl(@Nullable ByteString fileId, @NotNull HttpUrl url) {
            this.fileId = fileId;
            this.setUrl(url);
        }

        @NotNull
        HttpUrl url() throws CdnException {
            if (expiration == -1) return url;

            if (expiration <= System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5)) {
                try {
                    url = getAudioUrl(fileId);
                } catch (IOException | MercuryClient.MercuryException ex) {
                    throw new CdnException(ex);
                }
            }

            return url;
        }

        void setUrl(@NotNull HttpUrl url) {
            this.url = url;

            if (fileId != null) {
                String tokenStr = url.queryParameter("__token__");
                if (tokenStr != null && !tokenStr.isEmpty()) {
                    Long expireAt = null;
                    String[] split = tokenStr.split("~");
                    for (String str : split) {
                        int i = str.indexOf('=');
                        if (i == -1) continue;

                        if (str.substring(0, i).equals("exp")) {
                            expireAt = Long.parseLong(str.substring(i + 1));
                            break;
                        }
                    }

                    if (expireAt == null) {
                        expiration = -1;
                        LOGGER.warn("Invalid __token__ in CDN url: " + url);
                        return;
                    }

                    expiration = expireAt * 1000;
                } else {
                    String param = url.queryParameterName(0);
                    int i = param.indexOf('_');
                    if (i == -1) {
                        expiration = -1;
                        LOGGER.warn("Couldn't extract expiration, invalid parameter in CDN url: " + url);
                        return;
                    }

                    expiration = Long.parseLong(param.substring(0, i)) * 1000;
                }
            } else {
                expiration = -1;
            }
        }
    }

    public class Streamer implements GeneralAudioStream, GeneralWritableStream {
        private final StreamId streamId;
        private final ExecutorService executorService = Executors.newCachedThreadPool(new NameThreadFactory((r) -> "cdn-async-" + r.hashCode()));
        private final SuperAudioFormat format;
        private final AudioDecrypt audioDecrypt;
        private final CdnUrl cdnUrl;
        private final int size;
        private final byte[][] buffer;
        private final boolean[] available;
        private final boolean[] requested;
        private final int chunks;
        private final InternalStream internalStream;
        private final CacheManager.Handler cacheHandler;
        private final HaltListener haltListener;

        private Streamer(@NotNull StreamId streamId, @NotNull SuperAudioFormat format, @NotNull CdnUrl cdnUrl, @Nullable CacheManager cache,
                         @Nullable AudioDecrypt audioDecrypt, @Nullable HaltListener haltListener) throws IOException, CdnException {
            this.streamId = streamId;
            this.format = format;
            this.audioDecrypt = audioDecrypt;
            this.cdnUrl = cdnUrl;
            this.haltListener = haltListener;
            this.cacheHandler = cache != null ? cache.getHandler(streamId) : null;

            boolean fromCache;
            byte[] firstChunk;
            byte[] sizeHeader;
            if (cacheHandler != null && (sizeHeader = cacheHandler.getHeader(AudioFileFetch.HEADER_SIZE)) != null) {
                size = ByteBuffer.wrap(sizeHeader).getInt() * 4;
                chunks = (size + CHUNK_SIZE - 1) / CHUNK_SIZE;

                try {
                    firstChunk = cacheHandler.readChunk(0);
                    fromCache = true;
                } catch (IOException | CacheManager.BadChunkHashException ex) {
                    LOGGER.error("Failed getting first chunk from cache.", ex);

                    InternalResponse resp = request(0, CHUNK_SIZE - 1);
                    firstChunk = resp.buffer;
                    fromCache = false;
                }
            } else {
                InternalResponse resp = request(0, CHUNK_SIZE - 1);
                String contentRange = resp.headers.get("Content-Range");
                if (contentRange == null)
                    throw new IOException("Missing Content-Range header!");

                String[] split = Utils.split(contentRange, '/');
                size = Integer.parseInt(split[1]);
                chunks = (int) Math.ceil((float) size / (float) CHUNK_SIZE);

                if (cacheHandler != null)
                    cacheHandler.setHeader(AudioFileFetch.HEADER_SIZE, ByteBuffer.allocate(4).putInt(size / 4).array());

                firstChunk = resp.buffer;
                fromCache = false;
            }

            available = new boolean[chunks];
            requested = new boolean[chunks];

            buffer = new byte[chunks][];

            this.internalStream = new InternalStream(session.configuration().retryOnChunkError);
            writeChunk(firstChunk, 0, fromCache);
        }

        @Override
        public void writeChunk(@NotNull byte[] chunk, int chunkIndex, boolean cached) throws IOException {
            if (internalStream.isClosed()) return;

            if (!cached && cacheHandler != null) {
                try {
                    cacheHandler.writeChunk(chunk, chunkIndex);
                } catch (IOException ex) {
                    LOGGER.warn("Failed writing to cache! {index: {}}", chunkIndex, ex);
                }
            }

            LOGGER.trace("Chunk {}/{} completed, cached: {}, stream: {}", chunkIndex, chunks, cached, describe());

            buffer[chunkIndex] = chunk;
            audioDecrypt.decryptChunk(chunkIndex, chunk);
            internalStream.notifyChunkAvailable(chunkIndex);
        }

        @Override
        public @NotNull AbsChunkedInputStream stream() {
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

        @Override
        public int decryptTimeMs() {
            return audioDecrypt.decryptTimeMs();
        }

        private void requestChunk(int index) {
            if (cacheHandler != null) {
                try {
                    if (cacheHandler.hasChunk(index)) {
                        cacheHandler.readChunk(index, this);
                        return;
                    }
                } catch (IOException | CacheManager.BadChunkHashException ex) {
                    LOGGER.fatal("Failed requesting chunk from cache, index: {}", index, ex);
                }
            }

            try {
                InternalResponse resp = request(index);
                writeChunk(resp.buffer, index, false);
            } catch (IOException | CdnException ex) {
                LOGGER.fatal("Failed requesting chunk from network, index: {}", index, ex);
                internalStream.notifyChunkError(index, new AbsChunkedInputStream.ChunkException(ex));
            }
        }

        @NotNull
        public synchronized InternalResponse request(int chunk) throws IOException, CdnException {
            return request(CHUNK_SIZE * chunk, (chunk + 1) * CHUNK_SIZE - 1);
        }

        @NotNull
        public synchronized InternalResponse request(int rangeStart, int rangeEnd) throws IOException, CdnException {
            try (Response resp = session.client().newCall(new Request.Builder().get().url(cdnUrl.url())
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

        public int size() {
            return size;
        }

        private class InternalStream extends AbsChunkedInputStream {

            private InternalStream(boolean retryOnChunkError) {
                super(retryOnChunkError);
            }

            @Override
            public void close() {
                super.close();
                executorService.shutdown();

                if (cacheHandler != null) {
                    try {
                        cacheHandler.close();
                    } catch (IOException ignored) {
                    }
                }
            }

            @Override
            protected byte[][] buffer() {
                return buffer;
            }

            @Override
            public int size() {
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

            @Override
            public void streamReadHalted(int chunk, long time) {
                if (haltListener != null) executorService.submit(() -> haltListener.streamReadHalted(chunk, time));
            }

            @Override
            public void streamReadResumed(int chunk, long time) {
                if (haltListener != null) executorService.submit(() -> haltListener.streamReadResumed(chunk, time));
            }
        }
    }
}
