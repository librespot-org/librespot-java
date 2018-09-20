package org.librespot.spotify.player;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.core.PacketsManager;
import org.librespot.spotify.core.Session;
import org.librespot.spotify.crypto.Packet;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Gianlu
 */
public class ChannelManager extends PacketsManager {
    private static final int CHUNK_SIZE = 0x20000;
    public static final int OUR_CHUNK_SIZE = CHUNK_SIZE / 4;
    private static final Logger LOGGER = Logger.getLogger(ChannelManager.class);
    private final Map<Short, Channel> channels = new HashMap<>();
    private final AtomicInteger seqHolder = new AtomicInteger(0);

    public ChannelManager(@NotNull Session session) {
        super(session);
    }

    void requestChunk(ByteString fileId, int index, AudioFile file) throws IOException {
        int start = index * OUR_CHUNK_SIZE;
        int end = (index + 1) * OUR_CHUNK_SIZE;

        Channel channel = new Channel(file);
        channels.put(channel.id, channel);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);

        out.writeShort(channel.id);
        out.writeByte(0);
        out.writeByte(1);
        out.writeShort(0);
        out.writeInt(0);
        out.writeInt(0x00009C40);
        out.writeInt(0x00020000);
        fileId.writeTo(out);
        out.writeInt(start);
        out.writeInt(end);

        session.send(Packet.Type.StreamChunk, bytes.toByteArray());
    }

    @Override
    protected void handle(@NotNull Packet packet) throws IOException {
        ByteBuffer payload = ByteBuffer.wrap(packet.payload);
        if (packet.is(Packet.Type.StreamChunkRes)) {
            short id = payload.getShort();
            Channel channel = channels.get(id);
            if (channel == null) {
                LOGGER.warn(String.format("Couldn't find channel, id: %d, received: %d", id, packet.payload.length));
                return;
            }

            channel.handle(payload);
        } else if (packet.is(Packet.Type.ChannelError)) {
            short code = payload.getShort();
            LOGGER.fatal(String.format("Stream error, code: %d, length: %d", code, packet.payload.length));
        } else {
            LOGGER.warn(String.format("Couldn't handle packet, cmd: %s, length %d", packet.type(), packet.payload.length));
        }
    }

    @Override
    protected void exception(@NotNull Exception ex) {
        LOGGER.fatal("Failed handling packet!", ex);
    }

    public class Channel {
        public final short id;
        private final AudioFile file;
        private volatile boolean header = true;

        private Channel(@NotNull AudioFile file) {
            this.file = file;
            synchronized (seqHolder) {
                id = (short) seqHolder.getAndIncrement();
            }
        }

        private void handle(@NotNull ByteBuffer payload) throws IOException {
            if (payload.remaining() == 0) {
                if (!header) {
                    file.finishedChunk();
                    LOGGER.trace("Read last chunk packet.");
                    return;
                }

                LOGGER.trace("Received empty chunk, skipping.");
                return;
            }

            if (header) {
                short length;
                while ((length = payload.getShort()) > 0) {
                    byte headerId = payload.get();
                    byte[] headerData = new byte[length - 1];
                    payload.get(headerData);
                }

                header = false;
            } else {
                byte[] bytes = new byte[payload.remaining()];
                payload.get(bytes);
                file.write(bytes);
                file.flush();
            }
        }
    }
}
