package org.librespot.spotify.player;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.core.Session;
import org.librespot.spotify.proto.Metadata;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.security.GeneralSecurityException;

/**
 * @author Gianlu
 */
public class AudioFile {
    private static final Logger LOGGER = Logger.getLogger(AudioFile.class);
    private static final byte[] AUDIO_AES_IV = new byte[]{(byte) 0x72, (byte) 0xe0, (byte) 0x67, (byte) 0xfb, (byte) 0xdd, (byte) 0xcb, (byte) 0xcf, (byte) 0x77, (byte) 0xeb, (byte) 0xe8, (byte) 0xbc, (byte) 0x64, (byte) 0x3f, (byte) 0x63, (byte) 0x0d, (byte) 0x93};
    private final ByteString fileId;
    private final Session session;
    private final PipedInputStream in = new PipedInputStream(ChannelManager.OUR_CHUNK_SIZE);
    private final PipedOutputStream out;
    private final Cipher cipher;

    public AudioFile(@NotNull Session session, Metadata.AudioFile file, byte[] key) throws GeneralSecurityException {
        this.session = session;
        this.fileId = file.getFileId();

        cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(AUDIO_AES_IV));

        try {
            out = new PipedOutputStream(in);
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

    void write(byte[] buffer) throws IOException {
        out.write(cipher.update(buffer)); // FIXME: java.lang.ArrayIndexOutOfBoundsException: 16
    }

    void flush() throws IOException {
        out.flush();
    }

    void finishedChunk() {
        // TODO
    }
}
