package org.librespot.spotify.player;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.Utils;
import org.librespot.spotify.core.PacketsManager;
import org.librespot.spotify.core.Session;
import org.librespot.spotify.crypto.Packet;
import org.librespot.spotify.proto.Metadata;

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

    byte[] getAudioKey(Metadata.Track track, Metadata.AudioFile file) throws IOException, KeyErrorException {
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
        callbacks.put(seq, new Callback() {
            @Override
            public void key(byte[] key) {
                synchronized (ref) {
                    ref.set(key);
                    ref.notifyAll();
                }
            }

            @Override
            public void error(byte[] err) {
                synchronized (ref) {
                    ref.set(err);
                    ref.notifyAll();
                }
            }
        });

        byte[] result = Utils.wait(ref);
        if (result.length == 16) {
            return result;
        } else if (result.length == 2) {
            throw new KeyErrorException(result);
        } else {
            throw new IllegalStateException("Unknown payload: " + Utils.bytesToHex(result));
        }
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
            byte[] err = new byte[2];
            payload.get(err);
            callback.error(err);
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

        void error(byte[] err);
    }

    static class KeyErrorException extends Exception {
        KeyErrorException(byte[] err) {
            super("Error bytes: " + Utils.bytesToHex(err));
        }
    }
}
