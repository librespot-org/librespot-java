package xyz.gianlu.librespot.crypto;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Utils;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Gianlu
 */
public class CipherPair {
    private final Shannon sendCipher;
    private final Shannon recvCipher;
    private final AtomicInteger sendNonce;
    private final AtomicInteger recvNonce;

    public CipherPair(byte[] sendKey, byte[] recvKey) {
        sendCipher = new Shannon();
        sendCipher.key(sendKey);
        sendNonce = new AtomicInteger(0);

        recvCipher = new Shannon();
        recvCipher.key(recvKey);
        recvNonce = new AtomicInteger(0);
    }

    public void sendEncoded(OutputStream out, byte cmd, byte[] payload) throws IOException {
        synchronized (sendCipher) {
            sendCipher.nonce(Utils.toByteArray(sendNonce.getAndIncrement()));

            ByteBuffer buffer = ByteBuffer.allocate(1 + 2 + payload.length);
            buffer.put(cmd)
                    .putShort((short) payload.length)
                    .put(payload);

            byte[] bytes = buffer.array();
            sendCipher.encrypt(bytes);

            byte[] mac = new byte[4];
            sendCipher.finish(mac);

            out.write(bytes);
            out.write(mac);
            out.flush();
        }
    }

    @NotNull
    public Packet receiveEncoded(DataInputStream in) throws IOException, GeneralSecurityException {
        synchronized (recvCipher) {
            recvCipher.nonce(Utils.toByteArray(recvNonce.getAndIncrement()));

            byte[] headerBytes = new byte[3];
            in.readFully(headerBytes);
            recvCipher.decrypt(headerBytes);

            byte cmd = headerBytes[0];
            short payloadLength = (short) ((headerBytes[1] << 8) | (headerBytes[2] & 0xFF));

            byte[] payloadBytes = new byte[payloadLength];
            in.readFully(payloadBytes);
            recvCipher.decrypt(payloadBytes);

            byte[] mac = new byte[4];
            in.readFully(mac);

            byte[] expectedMac = new byte[4];
            recvCipher.finish(expectedMac);
            if (!Arrays.equals(mac, expectedMac)) throw new GeneralSecurityException("MACs don't match!");

            return new Packet(cmd, payloadBytes);
        }
    }
}
