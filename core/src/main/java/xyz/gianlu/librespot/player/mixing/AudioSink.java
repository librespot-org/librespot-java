package xyz.gianlu.librespot.player.mixing;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.player.Player;
import xyz.gianlu.librespot.player.codecs.Codec;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.*;

/**
 * @author devgianlu
 */
public final class AudioSink implements Runnable, Closeable {
    public static final AudioFormat OUTPUT_FORMAT = new AudioFormat(44100, 16, 2, true, false);
    private static final Logger LOGGER = LogManager.getLogger(AudioSink.class);
    private final Object pauseLock = new Object();
    private final Output output;
    private final MixingLine mixing = new MixingLine(OUTPUT_FORMAT);
    private final Thread thread;
    private final Listener listener;
    private volatile boolean closed = false;
    private volatile boolean paused = true;

    /**
     * Creates a new sink from the current {@param conf} with the standard {@link AudioSink#OUTPUT_FORMAT} format. Also sets the initial volume.
     */
    public AudioSink(@NotNull Player.Configuration conf, @NotNull Listener listener) {
        this.listener = listener;
        switch (conf.output()) {
            case MIXER:
                output = new Output(Output.Type.MIXER, mixing, conf, null, null);
                break;
            case PIPE:
                File pipe = conf.outputPipe();
                if (pipe == null || !pipe.exists() || !pipe.canWrite())
                    throw new IllegalArgumentException("Invalid pipe file: " + pipe);

                output = new Output(Output.Type.PIPE, mixing, conf, pipe, null);
                break;
            case STDOUT:
                output = new Output(Output.Type.STREAM, mixing, conf, null, System.out);
                break;
            default:
                throw new IllegalArgumentException("Unknown output: " + conf.output());
        }

        output.setVolume(conf.initialVolume());

        thread = new Thread(this, "player-audio-sink");
        thread.start();
    }

    /**
     * @return The {@link AudioFormat} that this sink accepts
     */
    @NotNull
    public AudioFormat getFormat() {
        return output.getFormat();
    }

    public void clearOutputs() {
        mixing.firstOut().clear();
        mixing.secondOut().clear();
    }

    /**
     * @return A free output stream or {@code null} if both are in use.
     */
    @Nullable
    public MixingLine.MixingOutput someOutput() {
        return mixing.someOut();
    }

    /**
     * Resumes the sink.
     */
    public void resume() {
        paused = false;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
    }

    /**
     * Pauses the sink and then releases the {@link javax.sound.sampled.Line} if specified by {@param release}.
     *
     * @return Whether the line was released.
     */
    public boolean pause(boolean release) {
        paused = true;

        if (release) return output.releaseLine();
        else return false;
    }

    /**
     * Flushes the sink.
     */
    public void flush() {
        output.flush();
    }

    /**
     * Sets the volume accordingly.
     *
     * @param volume The volume value from 0 to {@link Player#VOLUME_MAX}, inclusive.
     */
    public void setVolume(int volume) {
        if (volume < 0 || volume > Player.VOLUME_MAX)
            throw new IllegalArgumentException("Invalid volume: " + volume);

        output.setVolume(volume);
    }

    @Override
    public void close() {
        closed = true;
        thread.interrupt();

        clearOutputs();
    }

    @Override
    public void run() {
        byte[] buffer = new byte[Codec.BUFFER_SIZE * 2];

        boolean started = false;
        while (!closed) {
            if (paused) {
                output.stop();
                started = false;

                synchronized (pauseLock) {
                    try {
                        pauseLock.wait();
                    } catch (InterruptedException ex) {
                        break;
                    }
                }
            } else {
                try {
                    if (!started)
                        started = output.start();

                    int count = mixing.read(buffer);
                    output.write(buffer, count);
                } catch (IOException | LineUnavailableException | LineHelper.MixerException ex) {
                    if (closed) break;

                    pause(true);
                    listener.sinkError(ex);
                }
            }
        }

        try {
            output.drain();
            output.close();
        } catch (IOException ignored) {
        }
    }

    public interface Listener {
        void sinkError(@NotNull Exception ex);
    }

    private static class Output implements Closeable {
        private final File pipe;
        private final MixingLine mixing;
        private final Player.Configuration conf;
        private final Type type;
        private SourceDataLine line;
        private OutputStream out;
        private int lastVolume = -1;

        Output(@NotNull Type type, @NotNull MixingLine mixing, @NotNull Player.Configuration conf, @Nullable File pipe, @Nullable OutputStream out) {
            this.conf = conf;
            this.mixing = mixing;
            this.type = type;
            this.pipe = pipe;
            this.out = out;

            if (type == Type.PIPE && pipe == null)
                throw new IllegalArgumentException("Pipe cannot be null!");

            if (type == Type.STREAM && out == null)
                throw new IllegalArgumentException("Output stream cannot be null!");
        }

        private static float calcLogarithmic(int val) {
            return (float) (Math.log10((double) val / Player.VOLUME_MAX) * 20f);
        }

        private void acquireLine() throws LineUnavailableException, LineHelper.MixerException {
            if (line != null) return;

            line = LineHelper.getLineFor(conf, OUTPUT_FORMAT);
            line.open(OUTPUT_FORMAT);

            if (lastVolume != -1) setVolume(lastVolume);
        }

        void flush() {
            if (line != null) line.flush();
        }

        void stop() {
            if (line != null) line.stop();
        }

        boolean start() throws LineUnavailableException {
            if (type == Type.MIXER) {
                acquireLine();
                line.start();
                return true;
            }

            return false;
        }

        void write(byte[] buffer, int len) throws IOException, LineUnavailableException, LineHelper.MixerException {
            if (type == Type.MIXER) {
                line.write(buffer, 0, len);
            } else if (type == Type.PIPE) {
                if (out == null) {
                    if (!pipe.exists()) {
                        try {
                            Process p = new ProcessBuilder().command("mkfifo " + pipe.getAbsolutePath())
                                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
                            p.waitFor();
                            if (p.exitValue() != 0)
                                LOGGER.warn("Failed creating pipe! {exit: {}}", p.exitValue());
                            else
                                LOGGER.info("Created pipe: " + pipe);
                        } catch (InterruptedException ex) {
                            throw new IllegalStateException(ex);
                        }
                    }

                    out = new FileOutputStream(pipe, true);
                }

                out.write(buffer, 0, len);
            } else if (type == Type.STREAM) {
                out.write(buffer, 0, len);
            } else {
                throw new IllegalStateException();
            }
        }

        void drain() {
            if (line != null) line.drain();
        }

        @Override
        public void close() throws IOException {
            if (line != null) {
                line.close();
                line = null;
            }

            if (out != null) out.close();
        }

        @NotNull
        public AudioFormat getFormat() {
            if (line != null) return line.getFormat();
            else return OUTPUT_FORMAT;
        }

        void setVolume(int volume) {
            lastVolume = volume;

            if (line != null) {
                FloatControl ctrl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                if (ctrl != null) {
                    mixing.setGlobalGain(1);
                    ctrl.setValue(calcLogarithmic(volume));
                    return;
                }
            }

            // Cannot set volume through line
            mixing.setGlobalGain(((float) volume) / Player.VOLUME_MAX);
        }

        boolean releaseLine() {
            if (line == null) return false;

            line.close();
            line = null;
            return true;
        }

        enum Type {
            MIXER, PIPE, STREAM
        }
    }
}
