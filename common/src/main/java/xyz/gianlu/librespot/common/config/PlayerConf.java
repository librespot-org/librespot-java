package xyz.gianlu.librespot.common.config;

import xyz.gianlu.librespot.common.enums.AudioQuality;


public class PlayerConf {

    public final static int VOLUME_MAX = 65536;

    private AudioQuality preferredAudioQuality;
    private Float normalisationPregain = 0F;
    private String[] mixerSearchKeywords = new String[0];
    private Boolean logAvailableMixers = Boolean.TRUE;
    private int initialVolume = VOLUME_MAX;
    private Boolean autoplayEnabled = Boolean.TRUE;
    private Boolean useCdnForTracks = true;
    private Boolean useCdnForEpisodes = false;
    private Boolean enableLoadingState = true;
    private Boolean preloadEnabled = true;

    public AudioQuality getPreferredAudioQuality() {
        return preferredAudioQuality;
    }

    public void setPreferredAudioQuality(AudioQuality preferredAudioQuality) {
        this.preferredAudioQuality = preferredAudioQuality;
    }

    public Float getNormalisationPregain() {
        return normalisationPregain;
    }

    public void setNormalisationPregain(Float normalisationPregain) {
        this.normalisationPregain = normalisationPregain;
    }

    public String[] getMixerSearchKeywords() {
        return mixerSearchKeywords;
    }

    public void setMixerSearchKeywords(String[] mixerSearchKeywords) {
        this.mixerSearchKeywords = mixerSearchKeywords;
    }

    public Boolean getLogAvailableMixers() {
        return logAvailableMixers;
    }

    public void setLogAvailableMixers(Boolean logAvailableMixers) {
        this.logAvailableMixers = logAvailableMixers;
    }

    public int getInitialVolume() {
        return initialVolume;
    }

    public void setInitialVolume(int vol) {
        if (vol > 0 && vol < VOLUME_MAX) this.initialVolume = vol;
    }

    public Boolean getAutoplayEnabled() {
        return autoplayEnabled;
    }

    public void setAutoplayEnabled(Boolean autoplayEnabled) {
        this.autoplayEnabled = autoplayEnabled;
    }

    public Boolean getUseCdnForTracks() {
        return useCdnForTracks;
    }

    public void setUseCdnForTracks(Boolean useCdnForTracks) {
        this.useCdnForTracks = useCdnForTracks;
    }

    public Boolean getUseCdnForEpisodes() {
        return useCdnForEpisodes;
    }

    public void setUseCdnForEpisodes(Boolean useCdnForEpisodes) {
        this.useCdnForEpisodes = useCdnForEpisodes;
    }

    public Boolean getEnableLoadingState() {
        return enableLoadingState;
    }

    public void setEnableLoadingState(Boolean enableLoadingState) {
        this.enableLoadingState = enableLoadingState;
    }

    public Boolean getPreloadEnabled() {
        return preloadEnabled;
    }

    public void setPreloadEnabled(Boolean preloadEnabled) {
        this.preloadEnabled = preloadEnabled;
    }
}
