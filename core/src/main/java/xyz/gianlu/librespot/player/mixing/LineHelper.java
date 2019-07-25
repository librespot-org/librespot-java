package xyz.gianlu.librespot.player.mixing;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.player.Player;

import javax.sound.sampled.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author Gianlu
 */
public final class LineHelper {
    private static final Logger LOGGER = Logger.getLogger(LineHelper.class);

    private LineHelper() {
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
    private static Mixer findMixer(@NotNull List<Mixer> mixers, @Nullable String[] keywords) throws MixerException {
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
    public static SourceDataLine getLineFor(@NotNull Player.Configuration conf, @NotNull AudioFormat format) throws MixerException, LineUnavailableException {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format, AudioSystem.NOT_SPECIFIED);
        List<Mixer> mixers = findSupportingMixersFor(info);
        if (conf.logAvailableMixers()) LOGGER.info("Available mixers: " + Utils.mixersToString(mixers));
        Mixer mixer = findMixer(mixers, conf.mixerSearchKeywords());
        return (SourceDataLine) mixer.getLine(info);
    }

    public static class MixerException extends RuntimeException {
        MixerException(String message) {
            super(message);
        }
    }
}
