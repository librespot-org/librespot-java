package xyz.gianlu.librespot.player.mixing;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.player.codecs.Codec;

import java.io.*;

/**
 * @author Gianlu
 */
public class MixingLine extends InputStream {
    private final PipedInputStream fin;
    private final PipedInputStream sin;
    private final PipedOutputStream fout;
    private final PipedOutputStream sout;

    public MixingLine() {
        try {
            fin = new PipedInputStream(Codec.BUFFER_SIZE * 2);
            fin.connect(fout = new PipedOutputStream());
            sin = new PipedInputStream(Codec.BUFFER_SIZE * 2);
            sin.connect(sout = new PipedOutputStream());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static byte[] mix(byte[] first, byte[] second, int len) {
        byte[] result = new byte[len];

        for (int i = 0; i < len; i += 2) {
            int first16 = first[i] | (first[i + 1] << 8);
            int second16 = second[i] | (second[i + 1] << 8);

            int value = first16 + second16;
            if (value > 32767) value = 32767;
            else if (value < -32768) value = -32768;
            else if (value < 0) value = value | 32768;

            result[i] = (byte) value;
            result[i + 1] = (byte) (value >>> 8);
        }

        return result;
    }

    @NotNull
    public OutputStream firstOut() {
        return fout;
    }

    @NotNull
    public OutputStream secondOut() {
        return sout;
    }

    @Override
    public int read() throws IOException {
        return fin.read(); // TODO
    }

    @Override
    public int read(@NotNull byte[] b, int off, int len) throws IOException {
        return fin.read(b, off, len); // TODO
    }

    public void clearFirst() {
        try {
            fout.flush();
            fin.skip(fin.available());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public void clearSecond() {
        try {
            sout.flush();
            sin.skip(sin.available());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
