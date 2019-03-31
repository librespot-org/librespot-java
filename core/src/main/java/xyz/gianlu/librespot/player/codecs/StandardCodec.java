package xyz.gianlu.librespot.player.codecs;

import fr.delthas.javamp3.Sound;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.player.*;

import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;

/**
 * @author Gianlu
 */
public class StandardCodec extends Codec {
    private final LinesHolder.LineWrapper outputLine;
    private final byte[] buffer = new byte[BUFFER_SIZE];
    private final int size;
    private final Sound sound;
    private long readSoFar = 0;

    public StandardCodec(@NotNull GeneralAudioStream audioFile, @Nullable NormalizationData normalizationData, Player.@NotNull Configuration conf,
                         PlayerRunner.@NotNull Listener listener, @NotNull LinesHolder lines, int duration) throws CodecException, PlayerRunner.PlayerException, IOException {
        super(audioFile, normalizationData, conf, listener, lines, duration);

        audioIn.skip(3 + 2 + 1);

        byte[] sizeb = new byte[4];
        audioIn.read(sizeb);

        int tagsize = (sizeb[0] << 21) + (sizeb[1] << 14) + (sizeb[2] << 7) + sizeb[3];

        audioIn.skip(tagsize - 10);

        sound = new Sound(audioIn);

        try {
            size = sound.available();
        } catch (IOException ex) {
            throw new CodecException(ex);
        }

        try {
            outputLine = lines.getLineFor(conf, sound.getAudioFormat());
        } catch (LineUnavailableException | IllegalStateException | SecurityException ex) {
            throw new CodecException(ex);
        }
    }

    @Override
    public void read() {
        try {
            readBody();
            if (!stopped) listener.endOfTrack();
        } catch (IOException | LineUnavailableException ex) {
            if (!stopped) listener.playbackError(ex);
        } finally {
            cleanup();
        }
    }

    private void readBody() throws LineUnavailableException, IOException {
        SourceDataLine line = outputLine.waitAndOpen(sound.getAudioFormat());
        this.controller = new PlayerRunner.Controller(line, listener.getVolume());

        while (!stopped) {
            if (playing) {
                line.start();

                int count = sound.read(buffer);
                if (count == -1) break;

                readSoFar += count;

                line.write(buffer, 0, count);
            } else {
                line.stop();

                try {
                    synchronized (pauseLock) {
                        pauseLock.wait();
                    }
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

    @Override
    public int time() {
        return (int) (readSoFar * duration / size);
    }

    @Override
    public void cleanup() {
        outputLine.close();
    }

    @Override
    public void seek(int positionMs) {

    }
}
