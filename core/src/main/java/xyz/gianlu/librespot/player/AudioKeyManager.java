package xyz.gianlu.librespot.player;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.PacketsManager;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.crypto.Packet;

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
public final class AudioKeyManager extends PacketsManager {
    private static final byte[] ZERO_SHORT = new byte[]{0, 0};
    private static final Logger LOGGER = Logger.getLogger(AudioKeyManager.class);
    private static final long AUDIO_KEY_REQUEST_TIMEOUT = 2000;
    private final AtomicInteger seqHolder = new AtomicInteger(0);
    private final Map<Integer, Callback> callbacks = Collections.synchronizedMap(new HashMap<>());

    public AudioKeyManager(@NotNull Session session) {
        super(session, "audio-keys");
    }

    @NotNull
    public byte[] getAudioKey(@NotNull ByteString gid, @NotNull ByteString fileId) throws IOException {
        return getAudioKey(gid, fileId, true);
    }

    @NotNull
    private byte[] getAudioKey(@NotNull ByteString gid, @NotNull ByteString fileId, boolean retry) throws IOException {
        int seq;
        synchronized (seqHolder) {
            seq = seqHolder.getAndIncrement();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        fileId.writeTo(out);
        gid.writeTo(out);
        out.write(Utils.toByteArray(seq));
        out.write(ZERO_SHORT);

        session.send(Packet.Type.RequestKey, out.toByteArray());

        SyncCallback callback = new SyncCallback();
        callbacks.put(seq, callback);

        byte[] key = callback.waitResponse();
        if (key == null) {
            if (retry) return getAudioKey(gid, fileId, false);
            else throw new AesKeyException(String.format("Failed fetching audio key! {gid: %s, fileId: %s}",
                    Utils.bytesToHex(gid), Utils.bytesToHex(fileId)));
        }

        return key;
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
            callback.error(code);
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

        void error(short code);
    }

    private static class SyncCallback implements Callback {
        private final AtomicReference<byte[]> reference = new AtomicReference<>();

        @Override
        public void key(byte[] key) {
            synchronized (reference) {
                reference.set(key);
                reference.notifyAll();
            }
        }

        @Override
        public void error(short code) {
            LOGGER.fatal(String.format("Audio key error, code: %d", code));

            synchronized (reference) {
                reference.set(null);
                reference.notifyAll();
            }
        }

        @Nullable
        byte[] waitResponse() {
            synchronized (reference) {
                try {
                    reference.wait(AUDIO_KEY_REQUEST_TIMEOUT);
                    return reference.get();
                } catch (InterruptedException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        }
    }

    public static class AesKeyException extends IOException {
        AesKeyException(String message) {
            super(message);
        }
    }
}
