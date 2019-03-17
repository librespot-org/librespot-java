package xyz.gianlu.librespot.cdn;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.cache.CacheManager;
import xyz.gianlu.librespot.common.NetUtils;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.player.*;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
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

    public CdnManager(@NotNull Session session) {
        this.session = session;
    }

    @NotNull
    private InputStream getHead(@NotNull ByteString fileId) throws IOException { // TODO: Use this
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("heads-fa.spotify.com", 80));

        OutputStream out = socket.getOutputStream();
        out.write("GET ".getBytes());
        out.write("/head/".getBytes());
        out.write(Utils.bytesToHex(fileId).toLowerCase().getBytes());
        out.write(" HTTP/1.1".getBytes());
        out.write("\r\nHost: heads-fa.spotify.com".getBytes());
        out.write("\r\n\r\n".getBytes());
        out.flush();

        InputStream in = socket.getInputStream();
        NetUtils.StatusLine sl = NetUtils.parseStatusLine(Utils.readLine(in));
        if (sl.statusCode != 200)
            throw new IOException(sl.statusCode + ": " + sl.statusPhrase);

        Map<String, String> headers = NetUtils.parseHeaders(in);
        LOGGER.debug(String.format("Headers for %s: %s", Utils.bytesToHex(fileId), headers));
        return in;
    }

    @NotNull
    public Streamer stream(@NotNull ByteString fileId, @NotNull byte[] key) throws IOException, MercuryClient.MercuryException, CdnException {
        AudioUrl moreAudio = getAudioUrl(fileId);
        return new Streamer(fileId, key, moreAudio, session.cache());
    }

    @NotNull
    private AudioUrl getAudioUrl(@NotNull ByteString fileId) throws IOException, MercuryClient.MercuryException, CdnException {
        HttpURLConnection conn = (HttpURLConnection) new URL(String.format(STORAGE_RESOLVE_AUDIO_URL, Utils.bytesToHex(fileId))).openConnection();
        conn.addRequestProperty("Authorization", "Bearer " + session.tokens().get("playlist-read"));
        conn.connect();

        try {
            byte[] protoBytes;
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream bytesOut = new ByteArrayOutputStream()) {
                int count;
                byte[] buffer = new byte[4096];
                while ((count = in.read(buffer)) != -1)
                    bytesOut.write(buffer, 0, count);

                protoBytes = bytesOut.toByteArray();
            }

            StorageResolve.StorageResolveResponse proto = StorageResolve.StorageResolveResponse.parseFrom(protoBytes);
            if (proto.getResult() == StorageResolve.StorageResolveResponse.Result.CDN) {
                return new AudioUrl(proto.getCdnurl(session.random().nextInt(proto.getCdnurlCount())));
            } else {
                throw new CdnException(String.format("Could not retrieve CDN url! {result: %s}", proto.getResult()));
            }
        } finally {
            conn.disconnect();
        }
    }

    private static class AudioUrl {
        private final String host;
        private final String path;
        private final int port;

        private AudioUrl(@NotNull String str) throws MalformedURLException {
            URL url = new URL(str);

            this.host = url.getHost();
            this.port = url.getPort() == -1 ? url.getDefaultPort() : url.getPort();
            this.path = url.getPath() + "?" + url.getQuery();
        }

        @NotNull
        private Socket createSocket() throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        private void sendRequest(@NotNull OutputStream out, int rangeStart, int rangeEnd) throws IOException {
            out.write("GET ".getBytes());
            out.write(path.getBytes());
            out.write(" HTTP/1.1".getBytes());
            out.write("\r\nHost: ".getBytes());
            out.write(host.getBytes());
            out.write("\r\nRange: bytes=".getBytes());
            out.write(String.valueOf(rangeStart).getBytes());
            out.write("-".getBytes());
            out.write(String.valueOf(rangeEnd).getBytes());
            out.write("\r\n\r\n".getBytes());
            out.flush();
        }
    }

    public static class CdnException extends Exception {

        CdnException(@NotNull String message) {
            super(message);
        }
    }

    public static class Streamer implements GeneralAudioStream, GeneralWritableStream {
        private final ByteString fileId;
        private final ExecutorService executorService = Executors.newCachedThreadPool();
        private final AudioDecrypt audioDecrypt;
        private final int size;
        private final byte[][] buffer;
        private final boolean[] available;
        private final boolean[] requested;
        private final CdnLoader loader;
        private final int chunks;
        private final InternalStream internalStream;
        private final CacheManager.Handler cacheHandler;

        private Streamer(@NotNull ByteString fileId, byte[] key, @NotNull AudioUrl moreAudio, @Nullable CacheManager cache) throws IOException, CdnException {
            this.fileId = fileId;
            this.audioDecrypt = new AudioDecrypt(key);
            this.loader = new CdnLoader(moreAudio);
            this.cacheHandler = cache != null ? cache.forFileId(fileId) : null;

            byte[] firstChunk;
            try {
                List<CacheManager.Header> headers;
                if (cacheHandler == null || (headers = cacheHandler.getAllHeaders()).isEmpty()) {
                    CdnLoader.Response resp = loader.request(0, CHUNK_SIZE - 1);
                    String contentRange = resp.headers.get("Content-Range");
                    if (contentRange == null)
                        throw new CdnException("Missing Content-Range header!");

                    String[] split = Utils.split(contentRange, '/');
                    size = Integer.parseInt(split[1]);
                    chunks = (int) Math.ceil((float) size / (float) CHUNK_SIZE);

                    firstChunk = resp.buffer;

                    if (cacheHandler != null)
                        cacheHandler.setHeader(AudioFileFetch.HEADER_SIZE, ByteBuffer.allocate(4).putInt(size / 4).array());
                } else {
                    CacheManager.Header sizeHeader = CacheManager.Header.find(headers, AudioFileFetch.HEADER_SIZE);
                    if (sizeHeader == null)
                        throw new CdnException("Missing size header!");

                    size = ByteBuffer.wrap(sizeHeader.value).getInt() * 4;
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
                    throw new IOException(ex);
                }
            }

            LOGGER.trace(String.format("Chunk %d/%d completed, cdn: %s, cached: %b, fileId: %s", chunkIndex, chunks, loader.moreAudio.host, cached, Utils.bytesToHex(fileId)));

            audioDecrypt.decryptChunk(chunkIndex, chunk, buffer[chunkIndex]);
            internalStream.notifyChunkAvailable(chunkIndex);
        }

        @Override
        public @NotNull InputStream stream() {
            return internalStream;
        }

        @Override
        public @NotNull String getFileIdHex() {
            return Utils.bytesToHex(fileId);
        }

        private void requestChunk(int index) {
            try {
                if (cacheHandler != null && cacheHandler.hasChunk(index)) {
                    cacheHandler.readChunk(index, this);
                } else {
                    CdnLoader.Response resp = loader.request(index);
                    writeChunk(resp.buffer, index, false);
                }
            } catch (SQLException | IOException | CdnException ex) {
                LOGGER.fatal(String.format("Failed requesting chunk, index: %d", index), ex);
            }
        }

        private static class CdnLoader {
            private final Socket socket;
            private final DataInputStream in;
            private final OutputStream out;
            private final AudioUrl moreAudio;

            CdnLoader(@NotNull AudioUrl moreAudio) throws IOException {
                this.moreAudio = moreAudio;
                this.socket = moreAudio.createSocket();
                this.in = new DataInputStream(socket.getInputStream());
                this.out = socket.getOutputStream();
            }

            @NotNull
            public synchronized Response request(int chunk) throws IOException, CdnException {
                return request(CHUNK_SIZE * chunk, (chunk + 1) * CHUNK_SIZE - 1);
            }

            @NotNull
            public synchronized Response request(int rangeStart, int rangeEnd) throws IOException, CdnException {
                moreAudio.sendRequest(out, rangeStart, rangeEnd);

                NetUtils.StatusLine sl = NetUtils.parseStatusLine(Utils.readLine(in));
                if (sl.statusCode != 206)
                    throw new IOException(sl.statusCode + ": " + sl.statusPhrase);

                Map<String, String> headers = NetUtils.parseHeaders(in);
                String contentLengthStr = headers.get("Content-Length");
                if (contentLengthStr == null)
                    throw new CdnException("Missing Content-Length header!");

                int contentLength = Integer.parseInt(contentLengthStr);
                byte[] buffer = new byte[contentLength];
                in.readFully(buffer);

                return new Response(buffer, headers);
            }

            private static class Response {
                private final byte[] buffer;
                private final Map<String, String> headers;

                Response(byte[] buffer, Map<String, String> headers) {
                    this.buffer = buffer;
                    this.headers = headers;
                }
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
