package xyz.gianlu.librespot.cdn;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.cache.CacheManager;
import xyz.gianlu.librespot.common.BasicConnectionHolder;
import xyz.gianlu.librespot.common.NetUtils;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.player.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
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
    private InputStream getHead(@NotNull ByteString fileId) throws IOException {
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
        return new Streamer(fileId, key, getAudioUrl(fileId), session.cache());
    }

    @NotNull
    private BasicConnectionHolder getAudioUrl(@NotNull ByteString fileId) throws IOException, MercuryClient.MercuryException, CdnException {
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
                return new BasicConnectionHolder(proto.getCdnurl(session.random().nextInt(proto.getCdnurlCount())));
            } else {
                throw new CdnException(String.format("Could not retrieve CDN url! {result: %s}", proto.getResult()));
            }
        } finally {
            conn.disconnect();
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

        private Streamer(@NotNull ByteString fileId, byte[] key, @NotNull BasicConnectionHolder moreAudio, @Nullable CacheManager cache) throws IOException, CdnException {
            this.fileId = fileId;
            this.audioDecrypt = new AudioDecrypt(key);
            this.loader = new CdnLoader(moreAudio);
            this.cacheHandler = cache != null ? cache.forFileId(fileId) : null;

            byte[] firstChunk;
            try {
                byte[] sizeHeader;
                if (cacheHandler == null || (sizeHeader = cacheHandler.getHeader(AudioFileFetch.HEADER_SIZE)) == null) {
                    CdnLoader.Response resp = loader.request(0, CHUNK_SIZE - 1, false);
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

            LOGGER.trace(String.format("Chunk %d/%d completed, cdn: %s, cached: %b, fileId: %s", chunkIndex, chunks, loader.connHolder.host, cached, Utils.bytesToHex(fileId)));

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
            private final BasicConnectionHolder connHolder;
            private Socket socket;
            private DataInputStream in;
            private OutputStream out;

            CdnLoader(@NotNull BasicConnectionHolder connHolder) throws IOException {
                this.connHolder = connHolder;

                populateSocket();
            }

            private synchronized void populateSocket() throws IOException {
                this.socket = connHolder.createSocket();
                this.in = new DataInputStream(socket.getInputStream());
                this.out = socket.getOutputStream();
            }

            @NotNull
            public synchronized Response request(int chunk) throws IOException, CdnException {
                return request(CHUNK_SIZE * chunk, (chunk + 1) * CHUNK_SIZE - 1, false);
            }

            @NotNull
            public synchronized Response request(int rangeStart, int rangeEnd, boolean retried) throws IOException, CdnException {
                try {
                    if (socket == null || socket.isClosed())
                        populateSocket();

                    connHolder.sendGetRequest(out, rangeStart, rangeEnd);

                    NetUtils.StatusLine sl = NetUtils.parseStatusLine(Utils.readLine(in));
                    if (sl.statusCode == 408) {
                        socket.close();
                        return request(rangeStart, rangeEnd, false);
                    } else if (sl.statusCode != 206) {
                        throw new IOException(sl.statusCode + ": " + sl.statusPhrase);
                    }

                    Map<String, String> headers = NetUtils.parseHeaders(in);
                    String contentLengthStr = headers.get("Content-Length");
                    if (contentLengthStr == null)
                        throw new CdnException("Missing Content-Length header!");

                    int contentLength = Integer.parseInt(contentLengthStr);
                    byte[] buffer = new byte[contentLength];
                    in.readFully(buffer);

                    String connectionStr = headers.get("Connection");
                    if (Objects.equals(connectionStr, "close"))
                        socket.close();

                    return new Response(buffer, headers);
                } catch (IOException ex) {
                    if (!retried) {
                        if (socket != null) socket.close();
                        return request(rangeStart, rangeEnd, true);
                    } else {
                        throw ex;
                    }
                }
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
