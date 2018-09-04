package org.librespot.spotify.crypto;

import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * @author Gianlu
 */
public class ChiperPair {
    private final Shannon sendChiper;
    private final Shannon recvChiper;
    private int sendNonce;
    private int recvNonce;

    public ChiperPair(byte[] sendKey, byte[] recvKey) {
        sendChiper = new Shannon();
        sendChiper.key(sendKey);
        sendNonce = 0;

        recvChiper = new Shannon();
        recvChiper.key(recvKey);
        recvNonce = 0;
    }

    public void sendEncoded(OutputStream out, byte cmd, byte[] payload) throws IOException {
        sendChiper.nonce(Utils.toByteArray(sendNonce));
        sendNonce++;

        ByteBuffer buffer = ByteBuffer.allocate(1 + 2 + payload.length);
        buffer.put(cmd)
                .putShort((short) payload.length)
                .put(payload);

        byte[] bytes = buffer.array();
        sendChiper.encrypt(bytes);

        byte[] mac = new byte[4];
        sendChiper.finish(mac);

        out.write(bytes);
        out.write(mac);
    }

    @NotNull
    public Payload receiveEncoded(InputStream in) throws IOException, GeneralSecurityException {
        recvChiper.nonce(Utils.toByteArray(recvNonce));
        recvNonce++;

        byte[] headerBytes = new byte[3];
        if (in.read(headerBytes) != headerBytes.length) throw new IOException("Couldn't read header!");
        recvChiper.decrypt(headerBytes);

        byte cmd = headerBytes[0];
        short payloadLength = (short) ((headerBytes[1] << 8) | (headerBytes[2] & 0xFF));

        byte[] payloadBytes = new byte[payloadLength];
        if (in.read(payloadBytes) != payloadBytes.length) throw new IOException("Couldn't read payload!");
        recvChiper.decrypt(payloadBytes);

        byte[] mac = new byte[4];
        if (in.read(mac) != mac.length) throw new IOException("Couldn't read MAC!");

        byte[] expectedMac = new byte[4];
        recvChiper.finish(expectedMac);
        if (!Arrays.equals(mac, expectedMac)) throw new GeneralSecurityException("MACs don't match!");

        return new Payload(cmd, payloadBytes);
    }

    public static class Payload {
        public final byte cmd;
        public final byte[] payload;

        private Payload(byte cmd, byte[] payload) {
            this.cmd = cmd;
            this.payload = payload;
        }
    }
}
