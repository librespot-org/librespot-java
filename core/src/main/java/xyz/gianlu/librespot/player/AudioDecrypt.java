package xyz.gianlu.librespot.player;

import xyz.gianlu.librespot.common.Utils;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;

import static xyz.gianlu.librespot.player.ChannelManager.CHUNK_SIZE;

/**
 * @author Gianlu
 */
public class AudioDecrypt {
    private static final byte[] AUDIO_AES_IV = new byte[]{(byte) 0x72, (byte) 0xe0, (byte) 0x67, (byte) 0xfb, (byte) 0xdd, (byte) 0xcb, (byte) 0xcf, (byte) 0x77, (byte) 0xeb, (byte) 0xe8, (byte) 0xbc, (byte) 0x64, (byte) 0x3f, (byte) 0x63, (byte) 0x0d, (byte) 0x93};
    private final static BigInteger IV_INT = new BigInteger(1, AUDIO_AES_IV);
    private final SecretKeySpec secretKeySpec;

    public AudioDecrypt(byte[] key) {
        this.secretKeySpec = new SecretKeySpec(key, "AES");
    }

    public synchronized void decryptChunk(int chunkIndex, byte[] in, byte[] out) throws IOException {
        int pos = CHUNK_SIZE * chunkIndex;

        if (pos % 16 != 0) throw new IllegalArgumentException("Position not multiple of 16: " + pos);

        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, new IvParameterSpec(Utils.toByteArray(IV_INT.add(BigInteger.valueOf(pos / 16)))));

            for (int i = 0; i < in.length; i += 4096) {
                int endBytes = Math.min(i + 4096, in.length);
                int count = cipher.doFinal(in, 0, endBytes, out, 0);
                if (count != endBytes)
                    throw new IOException(String.format("Could process all data, actual: %d, expected: %d", count, endBytes));
            }
        } catch (GeneralSecurityException ex) {
            throw new IOException(ex);
        }
    }
}
