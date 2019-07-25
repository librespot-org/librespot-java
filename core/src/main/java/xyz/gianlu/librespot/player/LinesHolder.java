package xyz.gianlu.librespot.player;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;

import javax.sound.sampled.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Gianlu
 */
public class LinesHolder {
    private static final Logger LOGGER = Logger.getLogger(LinesHolder.class);
    private Mixer mixer;
    private LineWithState line;

    LinesHolder() {
    }

    @NotNull
    private static List<Mixer> findSupportingMixersFor(@NotNull Line.Info info) throws MixerException {
        List<Mixer> mixers = new ArrayList<>();
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            if (mixer.isLineSupported(info))
                mixers.add(mixer);
        }

        if (mixers.isEmpty())
            throw new MixerException(String.format("Couldn't find a suitable mixer, openLine: %s, available: %s", info, Arrays.toString(AudioSystem.getMixerInfo())));
        else
            return mixers;
    }

    @NotNull
    private Mixer findMixer(@NotNull List<Mixer> mixers, @Nullable String[] keywords) throws MixerException {
        if (mixer != null) return mixer;

        if (keywords == null || keywords.length == 0) return mixers.get(0);

        List<Mixer> list = new ArrayList<>(mixers);
        for (String word : keywords) {
            if (word == null) continue;

            list.removeIf(mixer -> !mixer.getMixerInfo().getName().toLowerCase().contains(word.toLowerCase()));
            if (list.isEmpty())
                throw new MixerException("No mixers available for the specified search keywords: " + Arrays.toString(keywords));
        }

        if (list.size() > 1)
            LOGGER.info("Multiple mixers available after keyword search: " + Utils.mixersToString(list));

        return list.get(0);
    }

    @NotNull
    public LineWrapper getLine(@NotNull DataLine.Info info) {
        return new LineWrapper(info);
    }

    @NotNull
    public LineWrapper getLineFor(@NotNull Player.Configuration conf, @NotNull AudioFormat format) throws MixerException {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format, AudioSystem.NOT_SPECIFIED);
        List<Mixer> mixers = findSupportingMixersFor(info);
        if (conf.logAvailableMixers()) LOGGER.info("Available mixers: " + Utils.mixersToString(mixers));
        mixer = findMixer(mixers, conf.mixerSearchKeywords());
        return getLine(info);
    }

    public static class MixerException extends Exception {
        MixerException(String message) {
            super(message);
        }
    }

    private static class LineWithState {
        private final SourceDataLine line;
        private final AtomicBoolean free = new AtomicBoolean(true);

        LineWithState(@NotNull SourceDataLine line) {
            this.line = line;
        }

        boolean isCompatible(@NotNull DataLine.Info otherInfo) {
            AudioFormat[] otherFormats = otherInfo.getFormats();
            if (otherFormats.length > 1) throw new UnsupportedOperationException();
            return line.getFormat().matches(otherFormats[0]);
        }

        void waitFreed() throws InterruptedException {
            synchronized (free) {
                if (free.get()) return;
                free.wait();
            }
        }

        void free() {
            synchronized (free) {
                free.set(true);
                free.notifyAll();
            }
        }

        void busy() {
            synchronized (free) {
                free.set(false);
            }
        }
    }

    public class LineWrapper {
        private final DataLine.Info info;

        private LineWrapper(@NotNull DataLine.Info info) {
            this.info = info;
        }

        public void write(byte[] buffer, int from, int to) {
            if (line == null) throw new IllegalStateException();
            line.line.write(buffer, from, to);
        }

        public void open(@NotNull AudioFormat format) throws LineUnavailableException, InterruptedException {
            if (line != null && line.isCompatible(info)) {
                line.waitFreed();

                LOGGER.trace(String.format("Reused line for mixer '%s'.", mixer.getMixerInfo().getName()));
            } else {
                if (line != null) {
                    line.waitFreed();
                    line.line.close();
                }

                line = new LineWithState((SourceDataLine) mixer.getLine(info));
                line.line.open(format);
                LOGGER.trace(String.format("New line opened for mixer '%s'.", mixer.getMixerInfo().getName()));
            }

            line.busy();
        }

        public void close() {
            if (line != null) line.free();
        }

        public void stop() {
            if (line == null) throw new IllegalStateException();
            line.line.stop();
        }

        public void start() {
            if (line == null) throw new IllegalStateException();
            line.line.start();
        }

        public boolean isControlSupported(@NotNull Control.Type type) {
            if (line == null) throw new IllegalStateException();
            return line.line.isControlSupported(type);
        }

        @NotNull
        public Control getControl(@NotNull Control.Type type) {
            if (line == null) throw new IllegalStateException();
            return line.line.getControl(type);
        }

        public void drain() {
            if (line == null) throw new IllegalStateException();
            line.line.drain();
        }
    }
}
