package xyz.gianlu.librespot.audio.decrypt;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Utils;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;

import static xyz.gianlu.librespot.audio.storage.ChannelManager.CHUNK_SIZE;

/**
 * @author Gianlu
 */
public final class AesAudioDecrypt implements AudioDecrypt {
    private static final byte[] AUDIO_AES_IV = new byte[]{(byte) 0x72, (byte) 0xe0, (byte) 0x67, (byte) 0xfb, (byte) 0xdd, (byte) 0xcb, (byte) 0xcf, (byte) 0x77, (byte) 0xeb, (byte) 0xe8, (byte) 0xbc, (byte) 0x64, (byte) 0x3f, (byte) 0x63, (byte) 0x0d, (byte) 0x93};
    private final static BigInteger IV_INT = new BigInteger(1, AUDIO_AES_IV);
    private static final BigInteger IV_DIFF = BigInteger.valueOf(0x100);
    private final SecretKeySpec secretKeySpec;
    private final Cipher cipher;
    private int decryptCount = 0;
    private long decryptTotalTime = 0;

    public AesAudioDecrypt(byte[] key) {
        try {
            this.secretKeySpec = new SecretKeySpec(key, "AES");
            this.cipher = Cipher.getInstance("AES/CTR/NoPadding");
        } catch (NoSuchAlgorithmException | NoSuchPaddingException ex) {
            throw new IllegalStateException(ex); // This should never happen
        }
    }

    @Override
    public synchronized void decryptChunk(int chunkIndex, @NotNull byte[] buffer) throws IOException {
        BigInteger iv = IV_INT.add(BigInteger.valueOf((long) CHUNK_SIZE * chunkIndex / 16));
        try {
            long start = System.nanoTime();
            for (int i = 0; i < buffer.length; i += 4096) {
                cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, new IvParameterSpec(Utils.toByteArray(iv)));

                int count = Math.min(4096, buffer.length - i);
                int processed = cipher.doFinal(buffer, i, count, buffer, i);
                if (count != processed)
                    throw new IOException(String.format("Couldn't process all data, actual: %d, expected: %d", processed, count));

                iv = iv.add(IV_DIFF);
            }

            decryptTotalTime += System.nanoTime() - start;
            decryptCount++;
        } catch (GeneralSecurityException ex) {
            throw new IOException(ex);
        }
    }

    /**
     * Average decrypt time for {@link xyz.gianlu.librespot.audio.storage.ChannelManager#CHUNK_SIZE} bytes of data.
     *
     * @return The average decrypt time in milliseconds
     */
    @Override
    public int decryptTimeMs() {
        return decryptCount == 0 ? 0 : (int) (((float) decryptTotalTime / decryptCount) / 1_000_000f);
    }
}
