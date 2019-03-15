package xyz.gianlu.librespot.cdn;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.NetUtils;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.player.AbsChunckedInputStream;
import xyz.gianlu.librespot.player.AudioDecrypt;
import xyz.gianlu.librespot.player.GeneralAudioStream;

import java.io.*;
import java.net.*;
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

        Map<String, String> headers = NetUtils.parseHeaders(in); // FIXME
        LOGGER.debug(String.format("Headers for %s: %s", Utils.bytesToHex(fileId), headers));
        return in;
    }

    @NotNull
    public Streamer stream(@NotNull ByteString fileId, @NotNull byte[] key) throws IOException, MercuryClient.MercuryException {
        AudioUrl moreAudio = getAudioUrl(fileId);
        return new Streamer(fileId, key, moreAudio);
    }

    @NotNull
    private AudioUrl getAudioUrl(@NotNull ByteString fileId) throws IOException, MercuryClient.MercuryException {
        HttpURLConnection conn = (HttpURLConnection) new URL(String.format(STORAGE_RESOLVE_AUDIO_URL, Utils.bytesToHex(fileId))).openConnection();
        conn.addRequestProperty("Authorization", "Bearer " + session.tokens().get("playlist-read"));
        conn.connect();

        byte[] protoBytes;
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream bytesOut = new ByteArrayOutputStream()) {
            int count;
            byte[] buffer = new byte[4096];
            while ((count = in.read(buffer)) != -1)
                bytesOut.write(buffer, 0, count);

            protoBytes = bytesOut.toByteArray();
        }

        StorageResolve.InteractiveAudioFiles proto = StorageResolve.InteractiveAudioFiles.parseFrom(protoBytes);
        return new AudioUrl(proto.getUris(session.random().nextInt(proto.getUrisCount())));
    }

    private static class AudioUrl {
        private final String host;
        private final String path;
        private final int port;

        private AudioUrl(@NotNull String str) throws MalformedURLException {
            URL url = new URL(str);

            System.out.println("URL: " + url);

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

    public static class Streamer implements GeneralAudioStream {
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

        private Streamer(@NotNull ByteString fileId, byte[] key, AudioUrl moreAudio) throws IOException {
            this.fileId = fileId;
            this.audioDecrypt = new AudioDecrypt(key);
            this.loader = new CdnLoader(moreAudio);
            this.internalStream = new InternalStream();

            CdnLoader.Response resp = loader.request(0, CHUNK_SIZE - 1);
            String contentRange = resp.headers.get("Content-Range");
            if (contentRange == null)
                throw new IllegalStateException("NOOOOOOO!"); // FIXME

            String[] split = Utils.split(contentRange, '/');
            size = Integer.parseInt(split[1]);
            chunks = size / CHUNK_SIZE;

            System.out.println("SIZE: " + size);
            System.out.println("CHUNKS: " + chunks);

            available = new boolean[chunks];
            requested = new boolean[chunks];

            buffer = new byte[chunks][CHUNK_SIZE];
            buffer[chunks - 1] = new byte[size % CHUNK_SIZE];

            writeChunk(resp.buffer, 0);
        }

        void writeChunk(@NotNull byte[] chunk, int chunkIndex) throws IOException {
            if (internalStream.isClosed()) return;

            // in.readFully(buffer[chunkIndex]);

            System.out.println("LENGTH: " + buffer[chunkIndex].length);

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
            public synchronized Response request(int rangeStart, int rangeEnd) throws IOException {
                LOGGER.trace(String.format("Sending request for %d to %d", rangeStart, rangeEnd));

                moreAudio.sendRequest(out, rangeStart, rangeEnd);

                NetUtils.StatusLine sl = NetUtils.parseStatusLine(Utils.readLine(in));
                if (sl.statusCode != 206)
                    throw new IOException(sl.statusCode + ": " + sl.statusPhrase);

                Map<String, String> headers = NetUtils.parseHeaders(in);
                System.out.println(headers);

                String contentLengthStr = headers.get("Content-Length");
                if (contentLengthStr == null)
                    throw new IllegalStateException("OH NO!"); // FIXME

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
                executorService.execute(() -> {
                    try {
                        CdnLoader.Response resp = loader.request(index * CHUNK_SIZE, (index + 1) * CHUNK_SIZE - 1);
                        writeChunk(resp.buffer, index);
                    } catch (IOException e) {
                        e.printStackTrace(); // FIXME
                    }
                });
            }
        }
    }
}
