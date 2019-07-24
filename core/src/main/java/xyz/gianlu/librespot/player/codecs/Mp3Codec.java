package xyz.gianlu.librespot.player.codecs;

import javazoom.jl.decoder.BitstreamException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.player.*;
import xyz.gianlu.librespot.player.codecs.mp3.Mp3InputStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import java.io.IOException;
import java.io.InputStream;

/**
 * @author Gianlu
 */
public class Mp3Codec extends Codec {
    private final LinesHolder.LineWrapper outputLine;
    private final byte[] buffer = new byte[BUFFER_SIZE];
    private final Mp3InputStream in;
    private final AudioFormat format;

    public Mp3Codec(@NotNull GeneralAudioStream audioFile, @Nullable NormalizationData normalizationData, Player.@NotNull Configuration conf,
                    PlayerRunner.@NotNull Listener listener, @NotNull LinesHolder lines, int duration) throws CodecException, IOException, LinesHolder.MixerException, BitstreamException {
        super(audioFile, normalizationData, conf, listener, lines, duration);

        skipMp3Tags(audioIn);

        this.in = new Mp3InputStream(audioIn);
        this.format = new AudioFormat(in.getSampleRate(), 16, in.getChannels(), true, in.isBigEndian());

        try {
            outputLine = lines.getLineFor(conf, format);
        } catch (IllegalStateException | SecurityException ex) {
            throw new CodecException(ex);
        }

        audioIn.mark(-1);
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
    protected void readBody() throws LineUnavailableException, IOException, InterruptedException {
        outputLine.open(format);
        this.controller = new PlayerRunner.Controller(outputLine, listener.getVolume());

        while (!stopped) {
            if (playing) {
                outputLine.start();

                int count = in.read(buffer);
                if (count == -1) break;

                outputLine.write(buffer, 0, count);
            } else {
                outputLine.stop();

                try {
                    synchronized (pauseLock) {
                        pauseLock.wait();
                    }
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }

        outputLine.drain();
    }

    @Override
    public int time() throws CannotGetTimeException {
        throw new CannotGetTimeException();
    }

    @Override
    public void cleanup() {
        outputLine.close();
        super.cleanup();
    }
}
