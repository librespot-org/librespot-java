package xyz.gianlu.librespot.player.feeders;

import javafx.fxml.LoadException;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.BasicConnectionHolder;
import xyz.gianlu.librespot.common.NetUtils;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.proto.Metadata;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.model.EpisodeId;
import xyz.gianlu.librespot.player.AbsChunckedInputStream;
import xyz.gianlu.librespot.player.GeneralAudioStream;
import xyz.gianlu.librespot.player.GeneralWritableStream;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Map;
import java.util.Objects;
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

    private static void resolveRedirects(@NotNull Loader loader) throws IOException {
        while (true) {
            if (loader.socket == null || loader.socket.isClosed())
                loader.populateSocket();

            loader.connHolder.sendHeadRequest(loader.out);

            NetUtils.StatusLine sl = NetUtils.parseStatusLine(Utils.readLine(loader.in));
            Map<String, String> headers = NetUtils.parseHeaders(loader.in);
            if (sl.statusCode == 408) {
                loader.socket.close();
            } else if (sl.statusCode == 206) {
                break;
            } else if (sl.statusCode == 302) {
                String locationStr = headers.get("Location");
                if (locationStr == null)
                    throw new IllegalStateException("WTF?!" /* FIXME */);

                loader.connHolder = new BasicConnectionHolder(locationStr);
            }
        }
    }

    @NotNull
    public LoadedStream load(@NotNull EpisodeId id, boolean cdn) throws IOException, MercuryClient.MercuryException, LoaderException {
        if (!cdn) throw new UnsupportedOperationException("NOT IMPLEMENTED YET!" /* FIXME */);

        Metadata.Episode resp = session.mercury().sendSync(MercuryRequests.getEpisode(id)).proto();

        String externalUrl = resp.getExternalUrl();
        if (externalUrl == null)
            throw new IllegalArgumentException("Missing external_url!");

        Loader loader = new Loader(new BasicConnectionHolder(externalUrl));
        resolveRedirects(loader);

        return new LoadedStream(resp, new EpisodeStream(id.hexId(), loader));
    }

    private static class Loader {
        private BasicConnectionHolder connHolder;
        private Socket socket;
        private DataInputStream in;
        private OutputStream out;

        Loader(@NotNull BasicConnectionHolder connHolder) throws IOException {
            this.connHolder = connHolder;
            populateSocket();
        }

        private synchronized void populateSocket() throws IOException {
            this.socket = connHolder.createSocket();
            this.in = new DataInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        @NotNull
        public synchronized Response request(int chunk) throws IOException, LoaderException {
            return request(CHUNK_SIZE * chunk, (chunk + 1) * CHUNK_SIZE - 1, false);
        }

        @NotNull
        public synchronized Response request(int rangeStart, int rangeEnd, boolean retried) throws IOException, LoaderException {
            try {
                if (socket == null || socket.isClosed())
                    populateSocket();

                connHolder.sendGetRequest(out, rangeStart, rangeEnd);

                NetUtils.StatusLine sl = NetUtils.parseStatusLine(Utils.readLine(in));
                Map<String, String> headers = NetUtils.parseHeaders(in);
                if (sl.statusCode == 408) {
                    socket.close();
                    return request(rangeStart, rangeEnd, false);
                } else if (sl.statusCode != 206) {
                    throw new IOException(sl.statusCode + ": " + sl.statusPhrase);
                }

                String contentLengthStr = headers.get("Content-Length");
                if (contentLengthStr == null)
                    throw new LoaderException("Missing Content-Length header!");

                int contentLength = Integer.parseInt(contentLengthStr);
                byte[] buffer = new byte[contentLength];
                in.readFully(buffer);

                String connectionStr = headers.get("Connection");
                if (Objects.equals(connectionStr, "close"))
                    socket.close();

                return new Loader.Response(buffer, headers);
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

    public static class LoaderException extends Exception {
        LoaderException(String message) {
            super(message);
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

        EpisodeStream(@NotNull String fileId, @NotNull Loader loader) throws IOException, LoaderException {
            this.fileId = fileId;
            this.loader = loader;

            byte[] firstChunk;
            Loader.Response resp = loader.request(0, CHUNK_SIZE - 1, false);
            String contentRange = resp.headers.get("Content-Range");
            if (contentRange == null)
                throw new LoadException("Missing Content-Range header!");

            String[] split = Utils.split(contentRange, '/');
            size = Integer.parseInt(split[1]);
            chunks = (int) Math.ceil((float) size / (float) CHUNK_SIZE);

            firstChunk = resp.buffer;

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
        public void writeChunk(@NotNull byte[] chunk, int chunkIndex, boolean cached) throws IOException {
            if (internalStream.isClosed()) return;

            if (chunk.length != buffer[chunkIndex].length)
                throw new IOException(String.format("Invalid buffer length, required: %d, provided: %d", buffer[chunkIndex].length, chunk.length));

            LOGGER.trace(String.format("Chunk %d/%d completed, host: %s, cached: %b, fileId: %s", chunkIndex, chunks, loader.connHolder.host, cached, fileId));

            buffer[chunkIndex] = chunk; // FIXME: We may need a copy

            internalStream.notifyChunkAvailable(chunkIndex);
        }

        private void requestChunk(int index) {
            try {
                Loader.Response resp = loader.request(index);
                writeChunk(resp.buffer, index, false);
            } catch (IOException | LoaderException ex) {
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
