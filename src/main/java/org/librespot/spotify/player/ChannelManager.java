package org.librespot.spotify.player;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.core.PacketsManager;
import org.librespot.spotify.core.Session;
import org.librespot.spotify.crypto.Packet;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Gianlu
 */
public class ChannelManager extends PacketsManager {
    private static final int CHUNK_SIZE = 0x20000;
    private static final Logger LOGGER = Logger.getLogger(ChannelManager.class);
    private final Map<Short, Channel> channels = new HashMap<>();
    private final AtomicInteger seqHolder = new AtomicInteger(0);

    public ChannelManager(@NotNull Session session) {
        super(session);
    }

    @NotNull Channel requestChunk(ByteString fileId, int index) throws IOException {
        int start = index * CHUNK_SIZE / 4;
        int end = (index + 1) * CHUNK_SIZE / 4;

        Channel channel = new Channel();
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

        return channel;
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

            // Read header(s)

            while (true) {
                short headerLength = payload.getShort();
                if (headerLength > 0) {
                    byte headerId = payload.get();
                    byte[] headerData = new byte[headerLength - 1];
                    payload.get(headerData);
                } else {
                    break;
                }
            }

            // Read data

            byte[] bytes = new byte[payload.remaining()];
            payload.get(bytes);
            channel.write(bytes);
            channel.flush();

            synchronized (this) {
                notifyAll();
            }
        } else if (packet.is(Packet.Type.ChannelError)) {
            short code = payload.getShort();
            LOGGER.fatal(String.format("Stream error, code: %d, length: %d", code, packet.payload.length));
        } else {
            LOGGER.warn(String.format("Couldn't handle packet, cmd: %s, length %d", packet.type(), packet.payload.length));
        }
    }

    @Override
    protected void exception(@NotNull Exception ex) {

    }

    public class Channel {
        public final short id;
        private final PipedOutputStream out;
        private final PipedInputStream in = new PipedInputStream();

        private Channel() throws IOException {
            synchronized (seqHolder) {
                id = (short) seqHolder.getAndIncrement();
            }

            out = new PipedOutputStream(in);
        }

        private void write(byte[] buffer) throws IOException {
            out.write(buffer);
        }

        private void flush() throws IOException {
            out.flush();
        }
    }
}
