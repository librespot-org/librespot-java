package org.librespot.spotify.player;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.core.Session;
import org.librespot.spotify.proto.Metadata;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.math.BigInteger;
import java.security.GeneralSecurityException;

/**
 * @author Gianlu
 */
public class AudioFile {
    private static final Logger LOGGER = Logger.getLogger(AudioFile.class);
    private static final byte[] AUDIO_AES_IV = new byte[]{(byte) 0x72, (byte) 0xe0, (byte) 0x67, (byte) 0xfb, (byte) 0xdd, (byte) 0xcb, (byte) 0xcf, (byte) 0x77, (byte) 0xeb, (byte) 0xe8, (byte) 0xbc, (byte) 0x64, (byte) 0x3f, (byte) 0x63, (byte) 0x0d, (byte) 0x93};
    private final ByteString fileId;
    private final Session session;
    private final PipedInputStream pipeIn = new PipedInputStream(ChannelManager.OUR_CHUNK_SIZE);
    private final BufferedInputStream in = new BufferedInputStream(pipeIn, ChannelManager.OUR_CHUNK_SIZE);
    private final DecryptOutputStream out;

    public AudioFile(@NotNull Session session, Metadata.AudioFile file, byte[] key) {
        this.session = session;
        this.fileId = file.getFileId();

        try {
            out = new DecryptOutputStream(new PipedOutputStream(pipeIn), key);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @NotNull
    public InputStream stream() {
        return in;
    }

    public void open() throws IOException {
        session.channel().requestChunk(fileId, 0, this);
    }

    void write(byte[] buffer, int chunkIndex) throws IOException {
        out.write(buffer, chunkIndex);
    }

    void flush() throws IOException {
        out.flush();
    }

    void finishedChunk() {
        // TODO
    }

    private static class DecryptOutputStream extends OutputStream {
        private final OutputStream out;
        private final SecretKeySpec secretKeySpec;
        private BigInteger ivInt;
        private BigInteger ivDiff;

        private DecryptOutputStream(OutputStream out, byte[] key) {
            this.out = out;
            this.secretKeySpec = new SecretKeySpec(key, "AES");
        }

        @Override
        public void write(int b) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(@NotNull byte[] b, int off, int len) {
            throw new UnsupportedOperationException();
        }

        void write(@NotNull byte[] buffer, int chunkIndex) throws IOException {
            int byteBaseOffset = chunkIndex * ChannelManager.OUR_CHUNK_SIZE * 4;

            ivInt = new BigInteger(AUDIO_AES_IV);
            ivDiff = BigInteger.valueOf((byteBaseOffset / 4096) * 0x100);
            ivInt = ivInt.add(ivDiff);

            ivDiff = BigInteger.valueOf(0x100);

            try {
                for (int i = 0; i < buffer.length; i += 4096) {
                    int endBytes = Math.min(i + 4096, buffer.length);

                    Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, new IvParameterSpec(ivInt.toByteArray()));
                    out.write(cipher.doFinal(buffer, 0, endBytes));

                    ivInt = ivInt.add(ivDiff);
                }
            } catch (GeneralSecurityException ex) {
                throw new IOException(ex);
            }
        }

        @Override
        public void write(@NotNull byte[] buffer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }
}
