package xyz.gianlu.librespot.player.codecs;

import javazoom.jl.decoder.BitstreamException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.player.GeneralAudioStream;
import xyz.gianlu.librespot.player.NormalizationData;
import xyz.gianlu.librespot.player.Player;
import xyz.gianlu.librespot.player.codecs.mp3.Mp3InputStream;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * @author Gianlu
 */
public class Mp3Codec extends Codec {
    private final byte[] buffer = new byte[2 * BUFFER_SIZE];
    private final Mp3InputStream in;

    public Mp3Codec(@NotNull GeneralAudioStream audioFile, @Nullable NormalizationData normalizationData, Player.@NotNull Configuration conf, int duration) throws IOException, BitstreamException {
        super(audioFile, normalizationData, conf, duration);

        skipMp3Tags(audioIn);
        this.in = new Mp3InputStream(audioIn, normalizationFactor);

        audioIn.mark(-1);

        setAudioFormat(new AudioFormat(in.getSampleRate(), 16, in.getChannels(), true, false));
    }

    private static void skipMp3Tags(@NotNull InputStream in) throws IOException {
        byte[] buffer = new byte[3];
        if (in.read(buffer) != 3)
            throw new IOException();

        if (!new String(buffer).equals("ID3")) {
            in.reset();
            return;
        }

        if (in.skip(3) != 3)
            throw new IOException();

        buffer = new byte[4];
        if (in.read(buffer) != 4)
            throw new IOException();

        int tagSize = (buffer[0] << 21) + (buffer[1] << 14) + (buffer[2] << 7) + buffer[3];
        tagSize -= 10;
        if (in.skip(tagSize) != tagSize)
            throw new IOException();
    }

    @Override
    public int readSome(@NotNull OutputStream out) throws IOException {
        if (closed) return -1;

        int count = in.read(buffer);
        if (count == -1) return -1;
        out.write(buffer, 0, count);
        out.flush();
        return count;
    }

    @Override
    public int time() throws CannotGetTimeException {
        throw new CannotGetTimeException();
    }

    @Override
    public void close() throws IOException {
        in.close();
        super.close();
    }
}
