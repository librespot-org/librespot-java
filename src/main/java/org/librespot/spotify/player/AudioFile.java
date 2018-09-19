package org.librespot.spotify.player;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.core.Session;
import org.librespot.spotify.crypto.Packet;
import org.librespot.spotify.proto.Metadata;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Gianlu
 */
public class AudioFile {
    private static final int CHUNK_SIZE = 0x20000;
    private static final Logger LOGGER = Logger.getLogger(AudioFile.class);
    private final ByteString gid;
    private final Session session;
    private final AtomicInteger seqHolder = new AtomicInteger(0);
    private Channel channel;

    public AudioFile(@NotNull Session session, Metadata.Track track) {
        this.session = session;
        this.gid = track.getGid();
    }

    public void open() throws IOException {
        requestChunk(0);
    }

    @NotNull
    public InputStream stream() {
        if (channel == null) throw new IllegalStateException("AudioFile not open!");
        return channel.in;
    }

    private void requestChunk(int index) throws IOException {
        int start = index * CHUNK_SIZE / 4;
        int end = (index + 1) * CHUNK_SIZE / 4;

        channel = new Channel();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);

        out.writeShort(channel.id);
        out.writeByte(0);
        out.writeByte(1);
        out.writeShort(0);
        out.writeInt(0);
        out.writeInt(0x00009C40);
        out.writeInt(0x00020000);
        gid.writeTo(out);
        out.writeInt(start);
        out.writeInt(end);

        session.send(Packet.Type.StreamChunk, bytes.toByteArray());

        synchronized (channel) {
            try {
                channel.wait();
            } catch (InterruptedException ex) {
                throw new IOException(ex);
            }
        }
    }

    public void handle(@NotNull Packet packet) {
        if (channel != null) {
            try {
                channel.handle(packet);
            } catch (IOException ex) {
                LOGGER.fatal(String.format("Failed reading from channel, cmd: %s, length %d", packet.type(), packet.payload.length));
            }
        } else {
            LOGGER.warn(String.format("Couldn't handle packet, cmd: %s, length %d", packet.type(), packet.payload.length));
        }
    }

    private class Channel {
        private final short id;
        private final PipedInputStream in = new PipedInputStream();
        private final PipedOutputStream out;

        Channel() throws IOException {
            synchronized (seqHolder) {
                this.id = (short) seqHolder.getAndIncrement();
            }

            this.out = new PipedOutputStream(in);
        }

        public void handle(@NotNull Packet packet) throws IOException {
            ByteBuffer payload = ByteBuffer.wrap(packet.payload);
            if (packet.is(Packet.Type.StreamChunkRes)) {
                short packetId = payload.getShort();
                if (id != packetId) {
                    LOGGER.warn(String.format("ID mismatch, channel: %d, received: %d", this.id, packetId));
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
                out.write(bytes);
                out.flush();

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
    }
}
