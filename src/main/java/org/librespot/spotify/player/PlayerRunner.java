package org.librespot.spotify.player;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;
import org.jetbrains.annotations.NotNull;

import javax.sound.sampled.*;
import java.io.IOException;
import java.io.InputStream;

/**
 * @author Gianlu
 */
public class PlayerRunner implements Runnable {
    private static final int BUFFER_SIZE = 2048;
    private static final int CONVERTED_BUFFER_SIZE = BUFFER_SIZE * 2;
    private final SyncState joggSyncState = new SyncState();
    private final InputStream audioIn;
    private final StreamState joggStreamState = new StreamState();
    private final DspState jorbisDspState = new DspState();
    private final Block jorbisBlock = new Block(jorbisDspState);
    private final Comment jorbisComment = new Comment();
    private final Info jorbisInfo = new Info();
    private final Packet joggPacket = new Packet();
    private final Page joggPage = new Page();
    private final float normalizationFactor;
    private byte[] buffer;
    private int count;
    private int index;
    private byte[] convertedBuffer;
    private SourceDataLine outputLine;
    private float[][][] pcmInfo;
    private int[] pcmIndex;
    private volatile boolean playing = false;
    private volatile boolean stopped = false;

    public PlayerRunner(@NotNull AudioFileStreaming audioFile, @NotNull NormalizationData normalizationData, @NotNull Player.Configuration configuration) throws IOException, PlaybackFailedException {
        this.audioIn = audioFile.stream();
        this.normalizationFactor = normalizationData.getFactor(configuration);

        this.joggSyncState.init();
        this.joggSyncState.buffer(BUFFER_SIZE);
        this.buffer = joggSyncState.data;

        readHeader();
        initializeSound();
    }

    private void readHeader() throws IOException, PlaybackFailedException {
        boolean finished = false;
        int packet = 1;

        while (!finished) {
            count = audioIn.read(buffer, index, BUFFER_SIZE);
            joggSyncState.wrote(count);

            if (packet == 1) {
                int result = joggSyncState.pageout(joggPage);
                if (result == -1) {
                    throw new PlaybackFailedException();
                } else if (result == 0) {
                    // Read more
                } else if (result == 1) {
                    joggStreamState.init(joggPage.serialno());
                    joggStreamState.reset();

                    jorbisInfo.init();
                    jorbisComment.init();

                    if (joggStreamState.pagein(joggPage) == -1)
                        throw new PlaybackFailedException();

                    if (joggStreamState.packetout(joggPacket) != 1)
                        throw new PlaybackFailedException();

                    if (jorbisInfo.synthesis_headerin(jorbisComment, joggPacket) < 0)
                        throw new PlaybackFailedException();

                    packet++;
                }
            } else if (packet == 2 || packet == 3) {
                int result = joggSyncState.pageout(joggPage);
                if (result == -1) {
                    throw new PlaybackFailedException();
                } else if (result == 0) {
                    // Read more
                } else if (result == 1) {
                    if (joggStreamState.pagein(joggPage) == -1)
                        throw new PlaybackFailedException();

                    if (joggStreamState.packetout(joggPacket) != 1)
                        throw new PlaybackFailedException();

                    if (jorbisInfo.synthesis_headerin(jorbisComment, joggPacket) < 0)
                        throw new PlaybackFailedException();

                    if (packet == 3) {
                        finished = true;
                    } else {
                        packet++;
                    }
                }
            }

            index = joggSyncState.buffer(BUFFER_SIZE);
            buffer = joggSyncState.data;

            if (count == 0 && !finished)
                throw new PlaybackFailedException();
        }
    }

    private void initializeSound() throws PlaybackFailedException {
        convertedBuffer = new byte[CONVERTED_BUFFER_SIZE];

        jorbisDspState.synthesis_init(jorbisInfo);
        jorbisBlock.init(jorbisDspState);

        int channels = jorbisInfo.channels;
        int rate = jorbisInfo.rate;

        AudioFormat audioFormat = new AudioFormat((float) rate, 16, channels, true, false);
        DataLine.Info datalineInfo = new DataLine.Info(SourceDataLine.class, audioFormat, AudioSystem.NOT_SPECIFIED);

        if (!AudioSystem.isLineSupported(datalineInfo))
            throw new PlaybackFailedException();

        try {
            outputLine = (SourceDataLine) AudioSystem.getLine(datalineInfo);
            outputLine.open(audioFormat);
        } catch (LineUnavailableException | IllegalStateException | SecurityException ex) {
            throw new PlaybackFailedException(ex);
        }

        pcmInfo = new float[1][][];
        pcmIndex = new int[jorbisInfo.channels];
    }

    private void readBody() throws PlaybackFailedException, IOException {
        while (!stopped) {
            if (playing) {
                outputLine.start();

                int result = joggSyncState.pageout(joggPage);
                if (result == -1 || result == 0) {
                    // Read more
                } else if (result == 1) {
                    if (joggStreamState.pagein(joggPage) == -1)
                        throw new PlaybackFailedException();

                    if (joggPage.granulepos() == 0)
                        break;

                    while (true) {
                        result = joggStreamState.packetout(joggPacket);
                        if (result == -1 || result == 0) {
                            break;
                        } else if (result == 1) {
                            decodeCurrentPacket();
                        }
                    }

                    if (joggPage.eos() != 0)
                        break;
                }

                index = joggSyncState.buffer(BUFFER_SIZE);
                buffer = joggSyncState.data;

                count = audioIn.read(buffer, index, BUFFER_SIZE);
                joggSyncState.wrote(count);

                if (count == 0)
                    break;
            } else {
                outputLine.stop();
            }
        }
    }

    private void decodeCurrentPacket() {
        if (jorbisBlock.synthesis(joggPacket) == 0)
            jorbisDspState.synthesis_blockin(jorbisBlock);

        int range;
        int samples;
        while ((samples = jorbisDspState.synthesis_pcmout(pcmInfo, pcmIndex)) > 0) {
            if (samples < CONVERTED_BUFFER_SIZE) range = samples;
            else range = CONVERTED_BUFFER_SIZE;

            for (int i = 0; i < jorbisInfo.channels; i++) {
                int sampleIndex = i * 2;
                for (int j = 0; j < range; j++) {
                    int value = (int) (pcmInfo[0][i][pcmIndex[i] + j] * 32767);
                    if (value > 32767) value = 32767;
                    else if (value < -32768) value = -32768;
                    else if (value < 0) value = value | 32768;

                    value *= normalizationFactor;

                    convertedBuffer[sampleIndex] = (byte) (value);
                    convertedBuffer[sampleIndex + 1] = (byte) (value >>> 8);

                    sampleIndex += 2 * (jorbisInfo.channels);
                }
            }

            outputLine.write(convertedBuffer, 0, 2 * jorbisInfo.channels * range);
            jorbisDspState.synthesis_read(range);
        }
    }

    private void cleanup() {
        joggStreamState.clear();
        jorbisBlock.clear();
        jorbisDspState.clear();
        jorbisInfo.clear();
        joggSyncState.clear();
    }

    @Override
    public void run() {
        try {
            readBody();
        } catch (PlaybackFailedException | IOException e) {
            e.printStackTrace();
        }

        cleanup();
    }

    public void play() {
        playing = true;
    }

    public void pause() {
        playing = false;
    }

    public void seek(int positionMs) {
        // TODO
    }

    public void stop() {
        stopped = true;
    }

    public static class PlaybackFailedException extends Exception {

        PlaybackFailedException() {
        }

        PlaybackFailedException(Throwable ex) {
            super(ex);
        }
    }
}
