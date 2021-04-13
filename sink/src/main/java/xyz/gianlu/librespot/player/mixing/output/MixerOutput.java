package xyz.gianlu.librespot.player.mixing.output;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * @author devgianlu
 */
@SuppressWarnings("unused")
public final class MixerOutput implements SinkOutput {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixerOutput.class);
    private final String[] mixerSearchKeywords;
    private final boolean logAvailableMixers;
    private SourceDataLine line;
    private float lastVolume = -1;

    public MixerOutput(@NotNull String[] mixerSearchKeywords, @NotNull Boolean logAvailableMixers) {
        this.mixerSearchKeywords = mixerSearchKeywords;
        this.logAvailableMixers = logAvailableMixers;
    }

    private static AudioFormat makeJavaxAudioFormat(@NotNull OutputAudioFormat format) {
        return new AudioFormat(new AudioFormat.Encoding(format.getEncoding()), format.getSampleRate(),
                format.getSampleSizeInBits(), format.getChannels(), format.getFrameSize(),
                format.getFrameRate(), format.isBigEndian());
    }

    private void acquireLine(@NotNull AudioFormat format) throws LineUnavailableException, LineHelper.MixerException {
        if (line == null || !line.getFormat().matches(format)) {
            if (line != null) line.close();

            try {
                line = LineHelper.getLineFor(mixerSearchKeywords, logAvailableMixers, format);
                line.open(format);
            } catch (LineUnavailableException | LineHelper.MixerException ex) {
                LOGGER.warn("Failed opening line for custom format '{}'. Opening default.", format);

                format = makeJavaxAudioFormat(OutputAudioFormat.DEFAULT_FORMAT);
                line = LineHelper.getLineFor(mixerSearchKeywords, logAvailableMixers, format);
                line.open(format);
            }
        }

        if (lastVolume != -1) setVolume(lastVolume);
    }

    @Override
    public void flush() {
        if (line != null) line.flush();
    }

    @Override
    public void stop() {
        if (line != null) line.stop();
    }

    @Override
    public boolean start(@NotNull OutputAudioFormat format) throws SinkException {
        try {
            acquireLine(makeJavaxAudioFormat(format));
            line.start();
            return true;
        } catch (LineUnavailableException | LineHelper.MixerException ex) {
            throw new SinkException("Failed acquiring line.", ex);
        }
    }

    @Override
    public void write(byte[] buffer, int offset, int len) {
        if (line != null) line.write(buffer, offset, len);
    }

    @Override
    public void drain() {
        if (line != null) line.drain();
    }

    @Override
    public void close() {
        if (line != null) {
            line.close();
            line = null;
        }
    }

    @Override
    public boolean setVolume(float volume) {
        lastVolume = volume;

        if (line != null) {
            FloatControl ctrl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            if (ctrl != null) {
                ctrl.setValue((float) (Math.log10(volume) * 20f));
                return true;
            } else {
                return false; // The line doesn't support volume control
            }
        } else {
            return true; // The line will be available at some point
        }
    }

    @Override
    public void release() {
        if (line != null) {
            line.close();
            line = null;
        }
    }
}
