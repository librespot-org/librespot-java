package xyz.gianlu.librespot.player;

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
import com.spotify.connectstate.Connect;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.ZeroconfServer;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.TimeProvider;
import xyz.gianlu.librespot.player.codecs.AudioQuality;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.function.Supplier;

/**
 * @author Gianlu
 */
public final class FileConfiguration {
    private static final Logger LOGGER = LogManager.getLogger(FileConfiguration.class);

    static {
        FormatDetector.registerExtension("properties", new PropertiesFormat());
    }

    private final CommentedFileConfig config;

    public FileConfiguration(@Nullable String... override) throws IOException {
        File confFile = null;
        if (override != null && override.length > 0) {
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

        config = CommentedFileConfig.builder(migrating ? new File("config.toml") : confFile).onFileNotFound(FileNotFoundAction.copyData(streamDefaultConfig())).build();
        config.load();

        if (migrating) {
            migrateOldConfig(confFile, config);
            config.save();
            if (!confFile.delete())
                LOGGER.warn("Failed deleting old configuration file.");

            LOGGER.info("Your configuration has been migrated to `config.toml`, change your input file if needed.");
        } else {
            updateConfigFile(new TomlParser().parse(streamDefaultConfig()));
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

    @NotNull
    private static InputStream streamDefaultConfig() {
        InputStream defaultConfig = FileConfiguration.class.getClassLoader().getResourceAsStream("default.toml");
        if (defaultConfig == null) throw new IllegalStateException();
        return defaultConfig;
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

    private @NotNull AudioQuality preferredQuality() {
        try {
            return config.getEnum("player.preferredAudioQuality", AudioQuality.class);
        } catch (IllegalArgumentException ex) { // Retro-compatibility
            LOGGER.warn("Please update the `player.preferredAudioQuality` option to either `NORMAL`, `HIGH` or `VERY_HIGH`.");

            String val = config.get("player.preferredAudioQuality");
            switch (val) {
                case "VORBIS_96":
                    return AudioQuality.NORMAL;
                case "VORBIS_160":
                    return AudioQuality.HIGH;
                case "VORBIS_320":
                    return AudioQuality.VERY_HIGH;
                default:
                    throw ex;
            }
        }
    }

    private @Nullable File outputPipe() {
        String path = config.get("player.pipe");
        if (path == null || path.isEmpty()) return null;
        return new File(path);
    }

    private @Nullable File metadataPipe() {
        String path = config.get("player.metadataPipe");
        if (path == null || path.isEmpty()) return null;
        return new File(path);
    }

    private float normalisationPregain() {
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

    private @Nullable String deviceId() {
        String val = config.get("deviceId");
        return val == null || val.isEmpty() ? null : val;
    }

    private @NotNull String deviceName() {
        return config.get("deviceName");
    }

    private @NotNull Connect.DeviceType deviceType() {
        return config.getEnum("deviceType", Connect.DeviceType.class);
    }

    private @NotNull String preferredLocale() {
        return config.get("preferredLocale");
    }

    private @NotNull String authUsername() {
        return config.get("auth.username");
    }

    private @NotNull String authPassword() {
        return config.get("auth.password");
    }

    private @NotNull String authBlob() {
        return config.get("auth.blob");
    }

    private @Nullable File credentialsFile() {
        String path = config.get("auth.credentialsFile");
        if (path == null || path.isEmpty()) return null;
        return new File(path);
    }

    public @NotNull Level loggingLevel() {
        return Level.toLevel(config.get("logLevel"));
    }

    @NotNull
    public FileConfiguration.AuthStrategy authStrategy() {
        return config.getEnum("auth.strategy", AuthStrategy.class);
    }

    public int apiPort() {
        return config.get("api.port");
    }

    public @NotNull String apiHost() {
        return config.get("api.host");
    }

    @NotNull
    public ZeroconfServer.Builder initZeroconfBuilder() {
        ZeroconfServer.Builder builder = new ZeroconfServer.Builder(toSession())
                .setPreferredLocale(preferredLocale())
                .setDeviceType(deviceType())
                .setDeviceName(deviceName())
                .setDeviceId(deviceId())
                .setListenPort(config.get("zeroconf.listenPort"));

        if (config.get("zeroconf.listenAll")) builder.setListenAll(true);
        else builder.setListenInterfaces(getStringArray("zeroconf.interfaces", ','));

        return builder;
    }

    @NotNull
    public Session.Builder initSessionBuilder() throws IOException, GeneralSecurityException {
        Session.Builder builder = new Session.Builder(toSession())
                .setPreferredLocale(preferredLocale())
                .setDeviceType(deviceType())
                .setDeviceName(deviceName())
                .setDeviceId(deviceId());

        switch (authStrategy()) {
            case FACEBOOK:
                builder.facebook();
                break;
            case BLOB:
                builder.blob(authUsername(), Base64.getDecoder().decode(authBlob()));
                break;
            case USER_PASS:
                builder.userPass(authUsername(), authPassword());
                break;
            case STORED:
                builder.stored();
                break;
            case ZEROCONF:
            default:
                throw new IllegalArgumentException(authStrategy().name());
        }

        return builder;
    }

    @NotNull
    public Session.Configuration toSession() {
        return new Session.Configuration.Builder()
                .setCacheEnabled(config.get("cache.enabled"))
                .setCacheDir(new File((String) config.get("cache.dir")))
                .setDoCacheCleanUp(config.get("cache.doCleanUp"))
                .setStoreCredentials(config.get("auth.storeCredentials"))
                .setStoredCredentialsFile(credentialsFile())
                .setTimeSynchronizationMethod(config.getEnum("time.synchronizationMethod", TimeProvider.Method.class))
                .setTimeManualCorrection(config.get("time.manualCorrection"))
                .setProxyEnabled(config.get("proxy.enabled"))
                .setProxyType(config.getEnum("proxy.type", Proxy.Type.class))
                .setProxyAddress(config.get("proxy.address"))
                .setProxyPort(config.get("proxy.port"))
                .setProxyAuth(config.get("proxy.auth"))
                .setProxyUsername(config.get("proxy.username"))
                .setProxyPassword(config.get("proxy.password"))
                .setRetryOnChunkError(config.get("player.retryOnChunkError"))
                .build();
    }

    @NotNull
    public PlayerConfiguration toPlayer() {
        return new PlayerConfiguration.Builder()
                .setAutoplayEnabled(config.get("player.autoplayEnabled"))
                .setCrossfadeDuration(config.get("player.crossfadeDuration"))
                .setEnableNormalisation(config.get("player.enableNormalisation"))
                .setInitialVolume(config.get("player.initialVolume"))
                .setLogAvailableMixers(config.get("player.logAvailableMixers"))
                .setMetadataPipe(metadataPipe())
                .setMixerSearchKeywords(getStringArray("player.mixerSearchKeywords", ';'))
                .setNormalisationPregain(normalisationPregain())
                .setOutput(config.getEnum("player.output", AudioOutput.class))
                .setOutputPipe(outputPipe())
                .setPreferredQuality(preferredQuality())
                .setPreloadEnabled(config.get("preload.enabled"))
                .setReleaseLineDelay(config.get("player.releaseLineDelay"))
                .setVolumeSteps(config.get("player.volumeSteps"))
                .setBypassSinkVolume(config.get("player.bypassSinkVolume"))
                .build();
    }

    public enum AuthStrategy {
        FACEBOOK, BLOB, USER_PASS, ZEROCONF, STORED
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
