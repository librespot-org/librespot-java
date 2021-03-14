package xyz.gianlu.librespot.player;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.player.codecs.AudioQuality;

import java.io.File;

/**
 * @author devgianlu
 */
public final class PlayerConfiguration {
    // Audio
    public final AudioQuality preferredQuality;
    public final boolean enableNormalisation;
    public final float normalisationPregain;
    public final boolean autoplayEnabled;
    public final int crossfadeDuration;
    public final boolean preloadEnabled;

    // Output
    public final AudioOutput output;
    public final File outputPipe;
    public final File metadataPipe;
    public final String[] mixerSearchKeywords;
    public final boolean logAvailableMixers;
    public final int releaseLineDelay;

    // Volume
    public final int initialVolume;
    public final int volumeSteps;
    public final boolean bypassSinkVolume;

    private PlayerConfiguration(AudioQuality preferredQuality, boolean enableNormalisation, float normalisationPregain, boolean autoplayEnabled, int crossfadeDuration, boolean preloadEnabled,
                                AudioOutput output, File outputPipe, File metadataPipe, String[] mixerSearchKeywords, boolean logAvailableMixers, int releaseLineDelay,
                                int initialVolume, int volumeSteps, boolean bypassSinkVolume) {
        this.preferredQuality = preferredQuality;
        this.enableNormalisation = enableNormalisation;
        this.normalisationPregain = normalisationPregain;
        this.autoplayEnabled = autoplayEnabled;
        this.crossfadeDuration = crossfadeDuration;
        this.output = output;
        this.outputPipe = outputPipe;
        this.metadataPipe = metadataPipe;
        this.mixerSearchKeywords = mixerSearchKeywords;
        this.logAvailableMixers = logAvailableMixers;
        this.releaseLineDelay = releaseLineDelay;
        this.initialVolume = initialVolume;
        this.volumeSteps = volumeSteps;
        this.preloadEnabled = preloadEnabled;
        this.bypassSinkVolume = bypassSinkVolume;
    }

    public enum AudioOutput {
        MIXER, PIPE, STDOUT
    }

    public final static class Builder {
        // Audio
        private AudioQuality preferredQuality = AudioQuality.NORMAL;
        private boolean enableNormalisation = true;
        private float normalisationPregain = 3.0f;
        private boolean autoplayEnabled = true;
        private int crossfadeDuration = 0;
        private boolean preloadEnabled = true;

        // Output
        private AudioOutput output = AudioOutput.MIXER;
        private File outputPipe;
        private File metadataPipe;
        private String[] mixerSearchKeywords;
        private boolean logAvailableMixers = true;
        private int releaseLineDelay = 20;

        // Volume
        private int initialVolume = Player.VOLUME_MAX;
        private int volumeSteps = 64;
        private boolean bypassSinkVolume = false;

        public Builder() {
        }

        public Builder setPreferredQuality(AudioQuality preferredQuality) {
            this.preferredQuality = preferredQuality;
            return this;
        }

        public Builder setEnableNormalisation(boolean enableNormalisation) {
            this.enableNormalisation = enableNormalisation;
            return this;
        }

        public Builder setNormalisationPregain(float normalisationPregain) {
            this.normalisationPregain = normalisationPregain;
            return this;
        }

        public Builder setAutoplayEnabled(boolean autoplayEnabled) {
            this.autoplayEnabled = autoplayEnabled;
            return this;
        }

        public Builder setCrossfadeDuration(int crossfadeDuration) {
            this.crossfadeDuration = crossfadeDuration;
            return this;
        }

        public Builder setOutput(AudioOutput output) {
            this.output = output;
            return this;
        }

        public Builder setOutputPipe(File outputPipe) {
            this.outputPipe = outputPipe;
            return this;
        }

        public Builder setMetadataPipe(File metadataPipe) {
            this.metadataPipe = metadataPipe;
            return this;
        }

        public Builder setMixerSearchKeywords(String[] mixerSearchKeywords) {
            this.mixerSearchKeywords = mixerSearchKeywords;
            return this;
        }

        public Builder setLogAvailableMixers(boolean logAvailableMixers) {
            this.logAvailableMixers = logAvailableMixers;
            return this;
        }

        public Builder setReleaseLineDelay(int releaseLineDelay) {
            this.releaseLineDelay = releaseLineDelay;
            return this;
        }

        public Builder setInitialVolume(int initialVolume) {
            if (initialVolume < 0 || initialVolume > Player.VOLUME_MAX)
                throw new IllegalArgumentException("Invalid volume: " + initialVolume);

            this.initialVolume = initialVolume;
            return this;
        }

        public Builder setVolumeSteps(int volumeSteps) {
            if (volumeSteps < 0 || volumeSteps > Player.VOLUME_MAX)
                throw new IllegalArgumentException("Invalid volume steps: " + volumeSteps);

            this.volumeSteps = volumeSteps;
            return this;
        }

        public Builder setPreloadEnabled(boolean preloadEnabled) {
            this.preloadEnabled = preloadEnabled;
            return this;
        }

        public Builder setBypassSinkVolume(boolean bypassSinkVolume) {
            this.bypassSinkVolume = bypassSinkVolume;
            return this;
        }

        @Contract(value = " -> new", pure = true)
        public @NotNull PlayerConfiguration build() {
            return new PlayerConfiguration(preferredQuality, enableNormalisation, normalisationPregain, autoplayEnabled, crossfadeDuration, preloadEnabled,
                    output, outputPipe, metadataPipe, mixerSearchKeywords, logAvailableMixers, releaseLineDelay,
                    initialVolume, volumeSteps, bypassSinkVolume);
        }
    }
}
