package xyz.gianlu.librespot;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.ConfigFormat;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.core.file.FormatDetector;
import com.electronwill.nightconfig.core.io.ConfigParser;
import com.electronwill.nightconfig.core.io.ConfigWriter;
import com.spotify.connectstate.model.Connect;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.ZeroconfServer;
import xyz.gianlu.librespot.player.PlayerRunner;
import xyz.gianlu.librespot.player.codecs.AudioQuality;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * @author Gianlu
 */
public final class FileConfiguration extends AbsConfiguration {
    private static final Logger LOGGER = Logger.getLogger(FileConfiguration.class);

    static {
        FormatDetector.registerExtension("properties", new PropertiesFormat());
    }

    private final CommentedFileConfig config;

    public FileConfiguration(@Nullable String[] override) throws IOException {
        File confFile = null;
        if (override != null) {
            for (String arg : override) {
                if (arg != null && arg.startsWith("--conf-file="))
                    confFile = new File(arg.substring(13));
            }
        }

        if (confFile == null) confFile = new File("config.toml");

        if (!confFile.exists()) {
            File oldConf = new File("conf.properties");
            if (oldConf.exists()) confFile = oldConf;
        }

        boolean migrating = FormatDetector.detect(confFile) instanceof PropertiesFormat;

        InputStream defaultConfig = FileConfiguration.class.getClassLoader().getResourceAsStream("default.toml");
        if (defaultConfig == null) throw new IllegalStateException();

        config = CommentedFileConfig.builder(migrating ? new File("config.toml") : confFile).onFileNotFound(FileNotFoundAction.copyData(defaultConfig)).build();
        config.load();

        if (migrating) {
            migrateOldConfig(confFile, config);
            config.save();
            confFile.delete();

            LOGGER.info("Your configuration has been migrated to `config.toml`, change your input file if needed.");
        }

        if (override != null && override.length > 0) {
            for (String str : override) {
                if (str == null) continue;

                if (str.contains("=") && str.startsWith("--")) {
                    String[] split = Utils.split(str, '=');
                    if (split.length != 2) {
                        LOGGER.warn("Invalid command line argument: " + str);
                        continue;
                    }

                    config.set(split[0].substring(2), split[1]);
                } else {
                    LOGGER.warn("Invalid command line argument: " + str);
                }
            }
        }
    }

    private static void migrateOldConfig(@NotNull File confFile, @NotNull FileConfig config) throws IOException {
        Properties old = new Properties();
        try (FileReader fr = new FileReader(confFile)) {
            old.load(fr);
        }

        for (Object key : old.keySet()) {
            String val = old.getProperty((String) key);
            if (Objects.equals(key, "player.normalisationPregain")) {
                config.set((String) key, Float.parseFloat(val));
            } else if ("true".equals(val) || "false".equals(val)) {
                config.set((String) key, Boolean.parseBoolean(val));
            } else {
                try {
                    int i = Integer.parseInt(val);
                    config.set((String) key, i);
                } catch (NumberFormatException ex) {
                    config.set((String) key, val);
                }
            }
        }
    }

    @NotNull
    private String[] getStringArray(@NotNull String key) {
        String str = config.get(key);
        if ((str = str.trim()).isEmpty()) return new String[0];
        else return Utils.split(str, ',');
    }

    @NotNull
    private File getFile(@NotNull String path) {
        return new File((String) config.get(path));
    }

    @Override
    public boolean cacheEnabled() {
        return config.get("cache.enabled");
    }

    @Override
    public @NotNull File cacheDir() {
        return getFile("cache.dir");
    }

    @Override
    public boolean doCleanUp() {
        return config.get("cache.doCleanUp");
    }

    @Override
    public @NotNull AudioQuality preferredQuality() {
        return config.getEnum("player.preferredAudioQuality", AudioQuality.class);
    }

    @Override
    public boolean preloadEnabled() {
        return config.get("preload.enabled");
    }

    @Override
    public float normalisationPregain() {
        Object raw = config.get("player.normalisationPregain");

        if (raw instanceof Double) return ((Double) raw).floatValue();
        else if (raw instanceof Integer) return ((Integer) raw).floatValue();
        else throw new IllegalArgumentException(" normalisationPregain is not a valid number: " + raw.toString());
    }

    @NotNull
    @Override
    public String[] mixerSearchKeywords() {
        return getStringArray("player.mixerSearchKeywords");
    }

    @Override
    public boolean logAvailableMixers() {
        return config.get("player.logAvailableMixers");
    }

    @Override
    public int initialVolume() {
        int vol = config.get("player.initialVolume");
        if (vol < 0 || vol > PlayerRunner.VOLUME_MAX)
            throw new IllegalArgumentException("Invalid volume: " + vol);

        return vol;
    }

    @Override
    public boolean autoplayEnabled() {
        return config.get("player.autoplayEnabled");
    }


    @Override
    public boolean useCdnForTracks() {
        return config.get("player.tracks.useCdn");
    }

    @Override
    public boolean useCdnForEpisodes() {
        return config.get("player.episodes.useCdn");
    }

    @Override
    public boolean enableLoadingState() {
        return config.get("player.enableLoadingState");
    }

    @Override
    public @Nullable String deviceName() {
        return config.get("deviceName");
    }

    @Override
    public @Nullable Connect.DeviceType deviceType() {
        return config.getEnum("deviceType", Connect.DeviceType.class);
    }

    @Override
    public @Nullable String authUsername() {
        return config.get("auth.username");
    }

    @Override
    public @Nullable String authPassword() {
        return config.get("auth.password");
    }

    @Override
    public @Nullable String authBlob() {
        return config.get("auth.blob");
    }

    @NotNull
    @Override
    public Strategy authStrategy() {
        return config.getEnum("auth.strategy", Strategy.class);
    }

    @Override
    public boolean zeroconfListenAll() {
        return config.get("zeroconf.listenAll");
    }

    @Override
    public int zeroconfListenPort() {
        int val = config.get("zeroconf.listenPort");
        if (val == -1) return val;

        if (val < ZeroconfServer.MIN_PORT || val > ZeroconfServer.MAX_PORT)
            throw new IllegalArgumentException("Illegal port number: " + val);

        return val;
    }

    @NotNull
    @Override
    public String[] zeroconfInterfaces() {
        return getStringArray("zeroconf.interfaces");
    }

    private final static class PropertiesFormat implements ConfigFormat<Config> {
        @Override
        public ConfigWriter createWriter() {
            return null;
        }

        @Override
        public ConfigParser<Config> createParser() {
            return null;
        }

        @Override
        public Config createConfig(Supplier<Map<String, Object>> mapCreator) {
            return null;
        }

        @Override
        public boolean supportsComments() {
            return false;
        }
    }
}
