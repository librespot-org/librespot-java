package xyz.gianlu.librespot.player;

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
import org.jetbrains.annotations.Nullable;

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
    private static final long TRACK_PRELOAD_THRESHOLD = 10; // sec
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
    private final LinesHolder lines;
    private final int duration;
    private final Object pauseLock = new Object();
    private final AudioFormat audioFormat;
    private final boolean preloadEnabled;
    private Controller controller;
    private byte[] buffer;
    private int count;
    private int index;
    private byte[] convertedBuffer;
    private LinesHolder.LineWrapper outputLine;
    private float[][][] pcmInfo;
    private int[] pcmIndex;
    private volatile boolean playing = false;
    private volatile boolean stopped = false;
    private long pcm_offset;
    private boolean calledPreload = false;

    PlayerRunner(@NotNull AudioFileStreaming audioFile, @NotNull NormalizationData normalizationData, @NotNull LinesHolder lines,
                 @NotNull Player.Configuration conf, @NotNull Listener listener, int duration) throws IOException, PlayerException {
        this.audioIn = audioFile.stream();
        this.lines = lines;
        this.duration = duration;
        this.listener = listener;
        this.normalizationFactor = normalizationData.getFactor(conf);

        this.joggSyncState.init();
        this.joggSyncState.buffer(BUFFER_SIZE);
        this.buffer = joggSyncState.data;

        readHeader();
        this.audioFormat = initializeSound(conf);
        this.preloadEnabled = conf.preloadEnabled();

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

    @NotNull
    private AudioFormat initializeSound(Player.Configuration conf) throws PlayerException {
        convertedBuffer = new byte[CONVERTED_BUFFER_SIZE];

        jorbisDspState.synthesis_init(jorbisInfo);
        jorbisBlock.init(jorbisDspState);

        int channels = jorbisInfo.channels;
        int rate = jorbisInfo.rate;

        AudioFormat format = new AudioFormat((float) rate, 16, channels, true, false);

        try {
            outputLine = lines.getLineFor(conf, format);
        } catch (LineUnavailableException | IllegalStateException | SecurityException ex) {
            throw new PlayerException(ex);
        }

        pcmInfo = new float[1][][];
        pcmIndex = new int[jorbisInfo.channels];

        return format;
    }

    /**
     * Reads the body. All "holes" (-1) are skipped, and the playback continues
     *
     * @throws PlayerException if a player exception occurs
     * @throws IOException     if an I/O exception occurs
     */
    private void readBody() throws PlayerException, IOException, LineUnavailableException {
        SourceDataLine line = outputLine.waitAndOpen(audioFormat);
        this.controller = new Controller(line, listener.getVolume());

        while (!stopped) {
            if (playing) {
                line.start();

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
                            decodeCurrentPacket(line);
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

    private void decodeCurrentPacket(@NotNull SourceDataLine line) {
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

                    sampleIndex += 2 * jorbisInfo.channels;
                }
            }

            line.write(convertedBuffer, 0, 2 * jorbisInfo.channels * range);
            jorbisDspState.synthesis_read(range);

            long granulepos = joggPacket.granulepos;
            if (granulepos != -1 && joggPacket.e_o_s == 0) {
                granulepos -= samples;
                pcm_offset = granulepos;
                checkPreload();
            }
        }
    }

    private void checkPreload() {
        if (preloadEnabled && !calledPreload && !stopped && (duration / 1000) - time() <= TRACK_PRELOAD_THRESHOLD) {
            calledPreload = true;
            listener.preloadNextTrack();
        }
    }

    public int time() {
        return (int) (pcm_offset / jorbisInfo.rate);
    }

    private void cleanup() {
        joggStreamState.clear();
        jorbisBlock.clear();
        jorbisDspState.clear();
        jorbisInfo.clear();
        joggSyncState.clear();
        outputLine.close();

        LOGGER.trace("Cleaned up player.");
    }

    @Override
    public void run() {
        try {
            readBody();
            if (!stopped) listener.endOfTrack();
        } catch (PlayerException | IOException | LineUnavailableException ex) {
            if (!stopped) listener.playbackError(ex);
        } finally {
            cleanup();
        }
    }

    void play() {
        playing = true;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
    }

    void pause() {
        playing = false;
    }

    void seek(int positionMs) {
        if (positionMs >= 0) {
            try {
                audioIn.reset();
                if (positionMs > 0) {
                    int skip = Math.round(audioIn.available() / (float) duration * positionMs);
                    if (skip > audioIn.available()) skip = audioIn.available();

                    long skipped = audioIn.skip(skip);
                    if (skip != skipped)
                        throw new IOException(String.format("Failed seeking, skip: %d, skipped: %d", skip, skipped));
                }
            } catch (IOException ex) {
                LOGGER.fatal("Failed seeking!", ex);
            }
        }
    }

    void stop() {
        stopped = true;
        synchronized (pauseLock) {
            pauseLock.notifyAll(); // Allow thread to exit
        }
    }

    @Nullable
    Controller controller() {
        return controller;
    }

    public interface Listener {
        void endOfTrack();

        void playbackError(@NotNull Exception ex);

        void preloadNextTrack();

        int getVolume();
    }

    public static class Controller {
        private final FloatControl masterGain;
        private int volume = 0;

        Controller(@NotNull Line line, int initialVolume) {
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN))
                masterGain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            else
                masterGain = null;

            setVolume(initialVolume);
        }

        private double calcLogarithmic(int val) {
            return Math.log10((double) val / VOLUME_MAX) * 20f;
        }

        public void setVolume(int val) {
            this.volume = val;

            if (masterGain != null)
                masterGain.setValue((float) calcLogarithmic(val));
        }

        int volumeDown() {
            setVolume(volume - VOLUME_STEP);
            return volume;
        }

        int volumeUp() {
            setVolume(volume + VOLUME_STEP);
            return volume;
        }
    }

    private static class NotVorbisException extends PlayerException {
    }

    private static class HoleInDataException extends PlayerException {
    }

    static class PlayerException extends Exception {

        private PlayerException() {
        }

        private PlayerException(@NotNull Throwable ex) {
            super(ex);
        }

        PlayerException(String message) {
            super(message);
        }
    }
}
