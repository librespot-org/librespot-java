package xyz.gianlu.librespot.player;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.Utils;
import xyz.gianlu.librespot.core.PacketsManager;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.crypto.Packet;
import xyz.gianlu.librespot.proto.Metadata;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Gianlu
 */
public class AudioKeyManager extends PacketsManager {
    private static final byte[] ZERO_SHORT = new byte[]{0, 0};
    private static final Logger LOGGER = Logger.getLogger(AudioKeyManager.class);
    private final AtomicInteger seqHolder = new AtomicInteger(0);
    private final Map<Integer, Callback> callbacks = Collections.synchronizedMap(new HashMap<>());

    public AudioKeyManager(@NotNull Session session) {
        super(session);
    }

    byte[] getAudioKey(@NotNull Metadata.Track track, @NotNull Metadata.AudioFile file) throws IOException {
        int seq;
        synchronized (seqHolder) {
            seq = seqHolder.getAndIncrement();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        file.getFileId().writeTo(out);
        track.getGid().writeTo(out);
        out.write(Utils.toByteArray(seq));
        out.write(ZERO_SHORT);

        session.send(Packet.Type.RequestKey, out.toByteArray());

        AtomicReference<byte[]> ref = new AtomicReference<>();
        callbacks.put(seq, key -> {
            synchronized (ref) {
                ref.set(key);
                ref.notifyAll();
            }
        });

        return Utils.wait(ref);
    }

    @Override
    protected void handle(@NotNull Packet packet) {
        ByteBuffer payload = ByteBuffer.wrap(packet.payload);
        int seq = payload.getInt();

        Callback callback = callbacks.remove(seq);
        if (callback == null) {
            LOGGER.warn("Couldn't find callback for seq: " + seq);
            return;
        }

        if (packet.is(Packet.Type.AesKey)) {
            byte[] key = new byte[16];
            payload.get(key);
            callback.key(key);
        } else if (packet.is(Packet.Type.AesKeyError)) {
            short code = payload.getShort();
            LOGGER.fatal(String.format("Audio key error, code: %d, length: %d", code, packet.payload.length));
        } else {
            LOGGER.warn(String.format("Couldn't handle packet, cmd: %s, length: %d", packet.type(), packet.payload.length));
        }
    }

    @Override
    protected void exception(@NotNull Exception ex) {
        LOGGER.fatal("Failed handling packet!", ex);
    }

    private interface Callback {
        void key(byte[] key);
    }
}
