package xyz.gianlu.librespot.player.feeders.storage;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.NameThreadFactory;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.PacketsManager;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.crypto.Packet;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Gianlu
 */
public class ChannelManager extends PacketsManager {
    public static final int CHUNK_SIZE = 128 * 1024;
    private static final Logger LOGGER = Logger.getLogger(ChannelManager.class);
    private final Map<Short, Channel> channels = new HashMap<>();
    private final AtomicInteger seqHolder = new AtomicInteger(0);
    private final ExecutorService executorService = Executors.newCachedThreadPool(new NameThreadFactory(r -> "channel-queue-" + r.hashCode()));

    public ChannelManager(@NotNull Session session) {
        super(session);
    }

    void requestChunk(@NotNull ByteString fileId, int index, @NotNull AudioFile file) throws IOException {
        int start = index * CHUNK_SIZE / 4;
        int end = (index + 1) * CHUNK_SIZE / 4;

        Channel channel = new Channel(file, index);
        channels.put(channel.id, channel);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);

        out.writeShort(channel.id);
        out.writeInt(0x00000000);
        out.writeInt(0x00000000);
        out.writeInt(0x00004e20);
        out.writeInt(0x00030d40);
        fileId.writeTo(out);
        out.writeInt(start);
        out.writeInt(end);

        session.send(Packet.Type.StreamChunk, bytes.toByteArray());
    }

    @Override
    protected void handle(@NotNull Packet packet) {
       throw new UnsupportedOperationException();
    }

    @Override
    protected void appendToQueue(@NotNull Packet packet) {
        ByteBuffer payload = ByteBuffer.wrap(packet.payload);
        if (packet.is(Packet.Type.StreamChunkRes)) {
            short id = payload.getShort();
            Channel channel = channels.get(id);
            if (channel == null) {
                LOGGER.warn(String.format("Couldn't find channel, id: %d, received: %d", id, packet.payload.length));
                return;
            }

            channel.addToQueue(payload);
        } else if (packet.is(Packet.Type.ChannelError)) {
            short id = payload.getShort();
            Channel channel = channels.get(id);
            if (channel == null) {
                LOGGER.warn(String.format("Dropping channel error, id: %d, code: %d", id, payload.getShort()));
                return;
            }

            channel.streamError(payload.getShort());
        } else {
            LOGGER.warn(String.format("Couldn't handle packet, cmd: %s, payload: %s", packet.type(), Utils.bytesToHex(packet.payload)));
        }
    }

    @Override
    protected void exception(@NotNull Exception ex) {
        LOGGER.fatal("Failed handling packet!", ex);
    }

    public class Channel {
        public final short id;
        private final BlockingQueue<ByteBuffer> queue = new LinkedBlockingQueue<>();
        private final AudioFile file;
        private final int chunkIndex;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(CHUNK_SIZE);
        private volatile boolean header = true;

        private Channel(@NotNull AudioFile file, int chunkIndex) {
            this.file = file;
            this.chunkIndex = chunkIndex;
            synchronized (seqHolder) {
                id = (short) seqHolder.getAndIncrement();
            }

            executorService.execute(new Handler());
        }

        /**
         * @return Whether the channel can be closed
         */
        private boolean handle(@NotNull ByteBuffer payload) throws IOException {
            if (payload.remaining() == 0) {
                if (!header) {
                    synchronized (buffer) {
                        file.writeChunk(buffer.toByteArray(), chunkIndex, false);
                        return true;
                    }
                }

                LOGGER.trace("Received empty chunk, skipping.");
                return false;
            }

            if (header) {
                short length;
                while (payload.remaining() > 0 && (length = payload.getShort()) > 0) {
                    byte headerId = payload.get();
                    byte[] headerData = new byte[length - 1];
                    payload.get(headerData);
                    file.writeHeader(headerId, headerData, false);
                }

                header = false;
            } else {
                byte[] bytes = new byte[payload.remaining()];
                payload.get(bytes);
                synchronized (buffer) {
                    buffer.write(bytes);
                }
            }

            return false;
        }

        private void addToQueue(@NotNull ByteBuffer payload) {
            queue.add(payload);
        }

        void streamError(short code) {
            file.streamError(chunkIndex, code);
        }

        private class Handler implements Runnable {

            @Override
            public void run() {
                while (true) {
                    try {
                        if (handle(queue.take())) {
                            channels.remove(id);
                            break;
                        }
                    } catch (InterruptedException | IOException ex) {
                        LOGGER.fatal("Failed handling packet!", ex);
                    }
                }
            }
        }
    }
}
