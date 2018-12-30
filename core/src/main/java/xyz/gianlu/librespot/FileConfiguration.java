package xyz.gianlu.librespot;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.player.TrackHandler;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

/**
 * @author Gianlu
 */
public final class FileConfiguration extends AbsConfiguration {
    private static final Logger LOGGER = Logger.getLogger(FileConfiguration.class);
    private final Properties properties;
    private final DefaultConfiguration defaults = new DefaultConfiguration();

    public FileConfiguration(@NotNull File file, @Nullable String[] override) throws IOException {
        this.properties = new Properties();
        this.properties.load(new FileReader(file));

        if (override != null && override.length > 0) {
            for (String str : override) {
                if (str == null) continue;

                if (str.contains("=") && str.startsWith("--")) {
                    String[] split = Utils.split(str, '=');
                    if (split.length != 2) {
                        LOGGER.warn("Invalid command line argument: " + str);
                        continue;
                    }

                    properties.setProperty(split[0].substring(2), split[1]);
                } else {
                    LOGGER.warn("Invalid command line argument: " + str);
                }
            }
        }
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

    @Contract("_, _, !null -> !null")
    private <E extends Enum<E>> E getEnum(@NotNull Class<E> clazz, @NotNull String key, @Nullable E fallback) {
        String val = properties.getProperty(key, null);
        if (val == null) return fallback;

        try {
            return Enum.valueOf(clazz, val);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    @NotNull
    private String[] getStringArray(@NotNull String key, @NotNull String[] fallback) {
        String str = properties.getProperty(key, null);
        if (str == null) return fallback;
        else if ((str = str.trim()).isEmpty()) return new String[0];
        else return Utils.split(str, ',');
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

    @Override
    public @Nullable String deviceName() {
        return properties.getProperty("deviceName", null);
    }

    @Override
    public @Nullable Session.DeviceType deviceType() {
        return getEnum(Session.DeviceType.class, "deviceType", null);
    }

    @Override
    public @Nullable String username() {
        return properties.getProperty("auth.username", null);
    }

    @Override
    public @Nullable String password() {
        return properties.getProperty("auth.password", null);
    }

    @Override
    public @Nullable String blob() {
        return properties.getProperty("auth.blob", null);
    }

    @NotNull
    @Override
    public Strategy strategy() {
        return getEnum(Strategy.class, "auth.strategy", defaults.strategy());
    }

    @Override
    public boolean zeroconfListenAll() {
        return getBoolean("zeroconf.listenAll", defaults.zeroconfListenAll());
    }

    @NotNull
    @Override
    public String[] zeroconfInterfaces() {
        return getStringArray("zeroconf.interfaces", defaults.zeroconfInterfaces());
    }
}
