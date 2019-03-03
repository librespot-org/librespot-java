package xyz.gianlu.librespot.player;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;

import javax.sound.sampled.*;
import java.util.*;

/**
 * @author Gianlu
 */
public class LinesHolder {
    private static final Logger LOGGER = Logger.getLogger(LinesHolder.class);
    private final Map<Mixer, List<Line>> openLines = new HashMap<>();
    private final Object waitLineLock = new Object();

    LinesHolder() {
    }

    @NotNull
    private static List<Mixer> findSupportingMixersFor(@NotNull Line.Info info) throws PlayerRunner.PlayerException {
        List<Mixer> mixers = new ArrayList<>();
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            if (mixer.isLineSupported(info))
                mixers.add(mixer);
        }

        if (mixers.isEmpty())
            throw new PlayerRunner.PlayerException(String.format("Couldn't find a suitable mixer, line: %s, available: %s", info, Arrays.toString(AudioSystem.getMixerInfo())));
        else
            return mixers;
    }

    @NotNull
    private static Mixer findMixer(@NotNull List<Mixer> mixers, @Nullable String[] keywords) throws PlayerRunner.PlayerException {
        if (keywords == null || keywords.length == 0) return mixers.get(0);

        List<Mixer> list = new ArrayList<>(mixers);
        for (String word : keywords) {
            if (word == null) continue;

            list.removeIf(mixer -> !mixer.getMixerInfo().getName().toLowerCase().contains(word.toLowerCase()));
            if (list.isEmpty())
                throw new PlayerRunner.PlayerException("No mixers available for the specified search keywords: " + Arrays.toString(keywords));
        }

        if (list.size() > 1)
            LOGGER.info("Multiple mixers available after keyword search: " + Utils.mixersToString(list));

        return list.get(0);
    }

    @NotNull
    public LineWrapper getLine(@NotNull Mixer mixer, @NotNull DataLine.Info info) throws LineUnavailableException {
        Line line = getLineOrNull(mixer, info);
        if (line == null) return new LineWrapper(mixer, info);
        else return new LineWrapper((SourceDataLine) line);
    }

    @Nullable
    private Line getLineOrNull(@NotNull Mixer mixer, @NotNull DataLine.Info info) throws LineUnavailableException {
        List<Line> lines;
        synchronized (openLines) {
            lines = openLines.computeIfAbsent(mixer, k -> new ArrayList<>());
            if (lines.isEmpty()) {
                Line line = mixer.getLine(info);
                lines.add(line);
                LOGGER.debug(String.format("Got first line from mixer '%s'", mixer.getMixerInfo().getName()));
                return line;
            }
        }

        int max = mixer.getMaxLines(info);
        LOGGER.debug(String.format("Mixer '%s' has %d lines in use, max: %d", mixer.getMixerInfo().getName(), lines.size(), max));

        if (max == AudioSystem.NOT_SPECIFIED) {
            Line line = mixer.getLine(info);
            lines.add(line);
            return line;
        } else {
            int count = 0;
            for (Line line : lines) {
                if (line.getLineInfo().matches(info))
                    count++;
            }

            if (max < count) {
                Line line = mixer.getLine(info);
                lines.add(line);
                return line;
            } else {
                return null;
            }
        }
    }

    @NotNull
    public LineWrapper getLineFor(@NotNull Player.Configuration conf, @NotNull AudioFormat format) throws PlayerRunner.PlayerException, LineUnavailableException {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format, AudioSystem.NOT_SPECIFIED);
        List<Mixer> mixers = findSupportingMixersFor(info);
        if (conf.logAvailableMixers()) LOGGER.info("Available mixers: " + Utils.mixersToString(mixers));
        Mixer mixer = findMixer(mixers, conf.mixerSearchKeywords());
        LOGGER.info(String.format("Mixer for playback '%s', maxLines: %d", mixer.getMixerInfo().getName(), mixer.getMaxLines(info)));
        return getLine(mixer, info);
    }

    private void lineClosed(@NotNull Line line) {
        synchronized (openLines) {
            for (List<Line> lines : openLines.values()) {
                if (lines.remove(line)) {
                    LOGGER.debug(String.format("Removed closed line, remaining: %d", lines.size()));
                    synchronized (waitLineLock) {
                        waitLineLock.notifyAll();
                        return;
                    }
                }
            }
        }
    }

    public class LineWrapper {
        private Mixer mixer;
        private DataLine.Info info;
        private SourceDataLine line;

        private LineWrapper(@NotNull Mixer mixer, @NotNull DataLine.Info info) {
            this.mixer = mixer;
            this.info = info;
        }

        private LineWrapper(@NotNull SourceDataLine line) {
            this.line = line;
        }

        @NotNull
        public SourceDataLine waitAndOpen(@NotNull AudioFormat format) throws LineUnavailableException {
            if (line != null) {
                line.open(format);
                return line;
            }

            if (info == null || mixer == null) throw new IllegalStateException("Line and info are both null!");

            while (line == null) {
                synchronized (waitLineLock) {
                    try {
                        waitLineLock.wait();
                        line = (SourceDataLine) getLineOrNull(mixer, info);
                    } catch (InterruptedException ex) {
                        LOGGER.fatal("Interrupted while waiting for line! Retrying.", ex);
                    }
                }
            }

            line.open(format);
            LOGGER.trace(String.format("Line opened for mixer '%s'.", mixer.getMixerInfo().getName()));
            return line;
        }

        public void close() {
            if (line != null) {
                line.close();
                lineClosed(line);
            }
        }
    }
}
