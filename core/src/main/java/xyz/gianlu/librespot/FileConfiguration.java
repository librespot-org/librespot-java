package xyz.gianlu.librespot;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.ConfigFormat;
import com.electronwill.nightconfig.core.UnmodifiableCommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.core.file.FormatDetector;
import com.electronwill.nightconfig.core.io.ConfigParser;
import com.electronwill.nightconfig.core.io.ConfigWriter;
import com.electronwill.nightconfig.toml.TomlParser;
import com.spotify.connectstate.model.Connect;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.TimeProvider;
import xyz.gianlu.librespot.core.ZeroconfServer;
import xyz.gianlu.librespot.player.AudioOutput;
import xyz.gianlu.librespot.player.PlayerRunner;
import xyz.gianlu.librespot.player.codecs.AudioQuality;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
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
                    confFile = new File(arg.substring(12));
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
        } else {
            updateConfigFile(new TomlParser().parse(defaultConfig));
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

                    String key = split[0].substring(2);
                    config.set(key, convertFromString(key, split[1]));
                } else {
                    LOGGER.warn("Invalid command line argument: " + str);
                }
            }
        }
    }

    private static boolean removeDeprecatedKeys(@NotNull Config defaultConfig, @NotNull Config config, @NotNull FileConfig base, @NotNull String prefix) {
        boolean save = false;

        for (Config.Entry entry : new ArrayList<>(config.entrySet())) {
            String key = prefix + entry.getKey();
            if (entry.getValue() instanceof Config) {
                if (removeDeprecatedKeys(defaultConfig, entry.getValue(), base, key + "."))
                    save = true;
            } else {
                if (!defaultConfig.contains(key)) {
                    LOGGER.trace("Removed entry from configuration file: " + key);
                    base.remove(key);
                    save = true;
                }
            }
        }

        return save;
    }

    private static boolean checkMissingKeys(@NotNull Config defaultConfig, @NotNull FileConfig config, @NotNull String prefix) {
        boolean save = false;

        for (Config.Entry entry : defaultConfig.entrySet()) {
            String key = prefix + entry.getKey();
            if (entry.getValue() instanceof Config) {
                if (checkMissingKeys(entry.getValue(), config, key + "."))
                    save = true;
            } else {
                if (!config.contains(key)) {
                    LOGGER.trace("Added new entry to configuration file: " + key);
                    config.set(key, entry.getValue());
                    save = true;
                }
            }
        }

        return save;
    }

    @NotNull
    private static Object convertFromString(@NotNull String key, @NotNull String value) {
        if (Objects.equals(key, "player.normalisationPregain")) {
            return Float.parseFloat(value);
        } else if (Objects.equals(key, "deviceType")) {
            if (value.equals("AudioDongle")) return "AUDIO_DONGLE";
            else return value.toUpperCase();
        } else if ("true".equals(value) || "false".equals(value)) {
            return Boolean.parseBoolean(value);
        } else {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ex) {
                return value;
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
            config.set((String) key, convertFromString((String) key, val));
        }
    }

    private void updateConfigFile(@NotNull CommentedConfig defaultConfig) {
        boolean save = checkMissingKeys(defaultConfig, config, "");
        if (removeDeprecatedKeys(defaultConfig, config, config, "")) save = true;

        if (save) {
            config.clearComments();

            for (Map.Entry<String, UnmodifiableCommentedConfig.CommentNode> entry : defaultConfig.getComments().entrySet()) {
                UnmodifiableCommentedConfig.CommentNode node = entry.getValue();
                if (config.contains(entry.getKey())) {
                    config.setComment(entry.getKey(), node.getComment());
                    Map<String, UnmodifiableCommentedConfig.CommentNode> children = node.getChildren();
                    if (children != null) ((CommentedConfig) config.getRaw(entry.getKey())).putAllComments(children);
                }
            }

            config.save();
        }
    }

    @NotNull
    private String[] getStringArray(@NotNull String key, char separator) {
        String str = config.get(key);
        if ((str = str.trim()).isEmpty()) return new String[0];
        else return Utils.split(str, separator);
    }

    @Override
    public boolean cacheEnabled() {
        return config.get("cache.enabled");
    }

    @Override
    public @NotNull File cacheDir() {
        return new File((String) config.get("cache.dir"));
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
    public @NotNull AudioOutput output() {
        return config.getEnum("player.output", AudioOutput.class);
    }

    @Override
    public @Nullable File outputPipe() {
        String path = config.get("player.pipe");
        if (path == null || path.isEmpty()) return null;
        return new File(path);
    }

    @Override
    public boolean preloadEnabled() {
        return config.get("preload.enabled");
    }

    @Override
    public float normalisationPregain() {
        Object raw = config.get("player.normalisationPregain");
        if (raw instanceof String) {
            return Float.parseFloat((String) raw);
        } else if (raw instanceof Double) {
            return ((Double) raw).floatValue();
        } else if (raw instanceof Integer) {
            return ((Integer) raw).floatValue();
        } else {
            throw new IllegalArgumentException(String.format("normalisationPregain is not a valid float: %s (%s) ", raw.toString(), raw.getClass()));
        }
    }

    @NotNull
    @Override
    public String[] mixerSearchKeywords() {
        return getStringArray("player.mixerSearchKeywords", ';');
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
    public int crossfadeDuration() {
        return config.get("player.crossfadeDuration");
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
        return getStringArray("zeroconf.interfaces", ',');
    }

    @Override
    public TimeProvider.@NotNull Method timeSynchronizationMethod() {
        return config.getEnum("time.synchronizationMethod", TimeProvider.Method.class);
    }

    @Override
    public int timeManualCorrection() {
        return config.get("time.manualCorrection");
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
