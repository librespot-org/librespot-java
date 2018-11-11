package org.librespot.spotify.player;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.proto.Spirc;

import javax.sound.sampled.*;
import java.io.IOException;
import java.io.InputStream;

/**
 * @author Gianlu
 */
public class PlayerRunner implements Runnable {
    public static final int VOLUME_STEPS = 64;
    public static final int VOLUME_MAX = 65536;
    private static final int VOLUME_STEP = 65536 / VOLUME_STEPS;
    private static final int BUFFER_SIZE = 2048;
    private static final int CONVERTED_BUFFER_SIZE = BUFFER_SIZE * 2;
    private static final Logger LOGGER = Logger.getLogger(PlayerRunner.class);
    private final SyncState joggSyncState = new SyncState();
    private final InputStream audioIn;
    private final Listener listener;
    private final StreamState joggStreamState = new StreamState();
    private final DspState jorbisDspState = new DspState();
    private final Block jorbisBlock = new Block(jorbisDspState);
    private final Comment jorbisComment = new Comment();
    private final Info jorbisInfo = new Info();
    private final Packet joggPacket = new Packet();
    private final Page joggPage = new Page();
    private final float normalizationFactor;
    private final Mixer mixer;
    private final Controller controller;
    private final int duration;
    private byte[] buffer;
    private int count;
    private int index;
    private byte[] convertedBuffer;
    private SourceDataLine outputLine;
    private float[][][] pcmInfo;
    private int[] pcmIndex;
    private volatile boolean playing = false;
    private volatile boolean stopped = false;
    private volatile boolean notifiedPlaybackReady = false;

    public PlayerRunner(@NotNull AudioFileStreaming audioFile, @NotNull NormalizationData normalizationData, @NotNull Spirc.DeviceState.Builder deviceState,
                        @NotNull Player.Configuration configuration, @NotNull Listener listener, int duration) throws IOException, PlayerException {
        this.audioIn = audioFile.stream();
        this.listener = listener;
        this.normalizationFactor = normalizationData.getFactor(configuration);
        this.mixer = AudioSystem.getMixer(AudioSystem.getMixerInfo()[0]);

        this.joggSyncState.init();
        this.joggSyncState.buffer(BUFFER_SIZE);
        this.buffer = joggSyncState.data;

        readHeader();
        initializeSound();
        this.controller = new Controller(outputLine, deviceState);
        this.duration = duration;

        audioIn.mark(-1);

        LOGGER.trace(String.format("Player ready for playback, fileId: %s", audioFile.getFileIdHex()));
    }

    /**
     * Reads the body. All "holes" (-1) in data will stop the playback.
     *
     * @throws PlayerException if a player exception occurs
     * @throws IOException     if an I/O exception occurs
     */
    private void readHeader() throws IOException, PlayerException {
        boolean finished = false;
        int packet = 1;

        while (!finished) {
            count = audioIn.read(buffer, index, BUFFER_SIZE);
            joggSyncState.wrote(count);

            int result = joggSyncState.pageout(joggPage);
            if (result == -1) {
                throw new HoleInDataException();
            } else if (result == 0) {
                // Read more
            } else if (result == 1) {
                if (packet == 1) {
                    joggStreamState.init(joggPage.serialno());
                    joggStreamState.reset();

                    jorbisInfo.init();
                    jorbisComment.init();
                }

                if (joggStreamState.pagein(joggPage) == -1)
                    throw new PlayerException();

                if (joggStreamState.packetout(joggPacket) == -1)
                    throw new HoleInDataException();

                if (jorbisInfo.synthesis_headerin(jorbisComment, joggPacket) < 0)
                    throw new NotVorbisException();

                if (packet == 3) finished = true;
                else packet++;
            }

            index = joggSyncState.buffer(BUFFER_SIZE);
            buffer = joggSyncState.data;

            if (count == 0 && !finished)
                throw new PlayerException();
        }
    }

    private void initializeSound() throws PlayerException {
        convertedBuffer = new byte[CONVERTED_BUFFER_SIZE];

        jorbisDspState.synthesis_init(jorbisInfo);
        jorbisBlock.init(jorbisDspState);

        int channels = jorbisInfo.channels;
        int rate = jorbisInfo.rate;

        AudioFormat audioFormat = new AudioFormat((float) rate, 16, channels, true, false);
        DataLine.Info dataLineInfo = new DataLine.Info(SourceDataLine.class, audioFormat, AudioSystem.NOT_SPECIFIED);

        if (!mixer.isLineSupported(dataLineInfo))
            throw new PlayerException();

        try {
            outputLine = (SourceDataLine) mixer.getLine(dataLineInfo);
            outputLine.open(audioFormat);
        } catch (LineUnavailableException | IllegalStateException | SecurityException ex) {
            throw new PlayerException(ex);
        }

        pcmInfo = new float[1][][];
        pcmIndex = new int[jorbisInfo.channels];
    }

    /**
     * Reads the body. All "holes" (-1) are skipped, and the playback continues
     *
     * @throws PlayerException if a player exception occurs
     * @throws IOException     if an I/O exception occurs
     */
    private void readBody() throws PlayerException, IOException {
        while (!stopped) {
            if (playing) {
                outputLine.start();

                int result = joggSyncState.pageout(joggPage);
                if (result == -1 || result == 0) {
                    // Read more
                } else if (result == 1) {
                    if (joggStreamState.pagein(joggPage) == -1)
                        throw new PlayerException();

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

                if (index == -1)
                    break;

                count = audioIn.read(buffer, index, BUFFER_SIZE);
                joggSyncState.wrote(count);

                if (count == 0)
                    break;
            } else {
                outputLine.stop();

                try {
                    Thread.sleep(500);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
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

            if (!notifiedPlaybackReady) {
                if (listener != null) listener.playbackReady();
                notifiedPlaybackReady = true;
            }
        }
    }

    private void cleanup() {
        joggStreamState.clear();
        jorbisBlock.clear();
        jorbisDspState.clear();
        jorbisInfo.clear();
        joggSyncState.clear();

        LOGGER.trace("Cleaned up player.");
    }

    @Override
    public void run() {
        try {
            readBody();
            if (!stopped) listener.endOfTrack();
        } catch (PlayerException | IOException ex) {
            if (!stopped) listener.playbackError(ex);
        } finally {
            cleanup();
        }
    }

    public void play() {
        playing = true;
    }

    public void pause() {
        playing = false;
    }

    public void seek(int positionMs) {
        if (positionMs > 0) {
            try {
                audioIn.reset();
                int skip = Math.round(audioIn.available() / (float) duration * positionMs);
                long skipped = audioIn.skip(skip);
                if (skip != skipped)
                    throw new IOException(String.format("Failed seeking, skip: %d, skipped: %d", skip, skipped));
            } catch (IOException ex) {
                LOGGER.fatal("Failed seeking!", ex);
            }
        }
    }

    public void stop() {
        stopped = true;
    }

    @NotNull
    public Controller controller() {
        return controller;
    }

    public interface Listener {
        void endOfTrack();

        void playbackReady();

        void playbackError(@NotNull Exception ex);
    }

    public static class Controller {
        private final FloatControl masterGain;
        private int volume = 0;

        Controller(@NotNull Line line, @NotNull Spirc.DeviceState.Builder deviceState) {
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                masterGain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                setVolume(deviceState.getVolume());
            } else {
                masterGain = null;
            }
        }

        private double calcLogarithmic(int val) {
            return Math.log10((double) val / VOLUME_MAX) * 20f;
        }

        public void setVolume(int val) {
            this.volume = val;

            if (masterGain != null)
                masterGain.setValue((float) calcLogarithmic(val));
        }

        public int volumeDown() {
            setVolume(volume - VOLUME_STEP);
            return volume;
        }

        public int volumeUp() {
            setVolume(volume + VOLUME_STEP);
            return volume;
        }
    }

    private static class NotVorbisException extends PlayerException {
    }

    private static class HoleInDataException extends PlayerException {
    }

    public static class PlayerException extends Exception {

        PlayerException() {
        }

        PlayerException(Throwable ex) {
            super(ex);
        }
    }
}
