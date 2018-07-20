package org.librespot.spotify.crypto;

import com.sun.xml.internal.messaging.saaj.util.ByteInputStream;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.Utils;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * @author Gianlu
 */
public class ShannonPair {
    private final Shannon recv_shannon;
    private final Shannon send_shannon;
    private int recv_nonce;
    private int send_nonce;

    ShannonPair(byte[] recv_key, byte[] send_key) {
        recv_nonce = 0;
        recv_shannon = new Shannon();
        recv_shannon.key(recv_key);

        send_nonce = 0;
        send_shannon = new Shannon();
        send_shannon.key(send_key);
    }

    @NotNull
    public DataInputStream readHeader(InputStream in) throws IOException {
        byte[] header = new byte[3];
        Utils.readChecked(in, header);
        recv_shannon.nonce(ByteBuffer.allocate(4).putInt(recv_nonce).array());
        recv_nonce += 1;
        recv_shannon.decrypt(header);
        return new DataInputStream(new ByteInputStream(header, header.length));
    }

    @NotNull
    public byte[] readPayload(int length, InputStream in) throws IOException {
        byte[] payload = new byte[length];
        Utils.readChecked(in, payload);
        recv_shannon.decrypt(payload);
        return payload;
    }

    @NotNull
    public byte[] encode(byte[] out) throws IOException {
        send_shannon.nonce(ByteBuffer.allocate(4).putInt(send_nonce).array());
        send_nonce += 1;
        send_shannon.encrypt(out);

        ByteArrayOutputStream enc = new ByteArrayOutputStream();
        enc.write(out);

        byte[] mac = new byte[4];
        send_shannon.finish(mac);
        enc.write(mac);
        return enc.toByteArray();
    }
}