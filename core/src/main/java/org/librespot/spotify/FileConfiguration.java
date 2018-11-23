package org.librespot.spotify;

import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.player.TrackHandler;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

/**
 * @author Gianlu
 */
public final class FileConfiguration extends AbsConfiguration {
    private final Properties properties;
    private final DefaultConfiguration defaults = new DefaultConfiguration();

    public FileConfiguration(@NotNull File file) throws IOException {
        this.properties = new Properties();
        this.properties.load(new FileReader(file));
    }

    private boolean getBoolean(@NotNull String key, boolean fallback) {
        return Boolean.parseBoolean(properties.getProperty(key, String.valueOf(fallback)));
    }

    @NotNull
    private File getFile(@NotNull String key, @NotNull File fallback) {
        return new File(properties.getProperty(key, fallback.getAbsolutePath()));
    }

    private float getFloat(@NotNull String key, float fallback) {
        try {
            return Float.parseFloat(properties.getProperty(key, String.valueOf(fallback)));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    @Override
    public boolean cacheEnabled() {
        return getBoolean("cache.enabled", defaults.cacheEnabled());
    }

    @Override
    public @NotNull File cacheDir() {
        return getFile("cache.dir", defaults.cacheDir());
    }

    @Override
    public boolean doCleanUp() {
        return getBoolean("cache.doCleanUp", defaults.doCleanUp());
    }

    @Override
    public @NotNull TrackHandler.AudioQuality preferredQuality() {
        return TrackHandler.AudioQuality.valueOf(properties.getProperty("player.preferredAudioQuality", defaults.preferredQuality().name()));
    }

    @Override
    public boolean preloadEnabled() {
        return getBoolean("preload.enabled", defaults.preloadEnabled());
    }

    @Override
    public float normalisationPregain() {
        return getFloat("player.normalisationPregain", defaults.normalisationPregain());
    }
}
