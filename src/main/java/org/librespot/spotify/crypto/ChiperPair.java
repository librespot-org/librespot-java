package org.librespot.spotify.crypto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
    public ChiperPair.Packet receiveEncoded(InputStream in) throws IOException, GeneralSecurityException {
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

        return new Packet(cmd, payloadBytes);
    }

    public enum PacketType {
        SecretBlock(0x02),
        Ping(0x04),
        StreamChunk(0x08),
        StreamChunkRes(0x09),
        ChannelError(0x0a),
        ChannelAbort(0x0b),
        RequestKey(0x0c),
        AesKey(0x0d),
        AesKeyError(0x0e),
        Image(0x19),
        CountryCode(0x1b),
        Pong(0x49),
        PongAck(0x4a),
        Pause(0x4b),
        ProductInfo(0x50),
        LegacyWelcome(0x69),
        LicenseVersion(0x76),
        Login(0xab),
        APWelcome(0xac),
        AuthFailure(0xad),
        MercuryReq(0xb2),
        MercurySub(0xb3),
        MercuryUnsub(0xb4);

        public final byte val;

        PacketType(int val) {
            this.val = (byte) val;
        }

        @Nullable
        public static PacketType parse(byte val) {
            for (PacketType cmd : values())
                if (cmd.val == val)
                    return cmd;

            return null;
        }
    }

    public static class Packet {
        public final byte cmd;
        public final byte[] payload;

        private Packet(byte cmd, byte[] payload) {
            this.cmd = cmd;
            this.payload = payload;
        }

        @Nullable
        public PacketType type() {
            return PacketType.parse(cmd);
        }
    }
}
