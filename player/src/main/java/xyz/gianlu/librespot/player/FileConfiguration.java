/*
 * Copyright 2022 devgianlu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gianlu.librespot.ZeroconfServer;
import xyz.gianlu.librespot.audio.decoders.AudioQuality;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.TimeProvider;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * @author devgianlu
 */
public final class FileConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileConfiguration.class);

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

    @Nullable
    private File getFile(@NotNull String key) {
        String path = config.get(key);
        return path == null || path.isEmpty() ? null : new File(path);
    }

    @NotNull
    private String[] getStringArray(@NotNull String key, char separator) {
        String str = config.get(key);
        if ((str = str.trim()).isEmpty()) return new String[0];
        else return Utils.split(str, separator);
    }

    @NotNull
    private AudioQuality preferredQuality() {
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

    @Nullable
    private File outputPipe() {
        String path = config.get("player.pipe");
        if (path == null || path.isEmpty()) return null;
        return new File(path);
    }

    @Nullable
    private File metadataPipe() {
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

    @Nullable
    private String deviceId() {
        String val = config.get("deviceId");
        return val == null || val.isEmpty() ? null : val;
    }

    @Nullable
    private String clientToken() {
        String val = config.get("clientToken");
        return val == null || val.isEmpty() ? null : val;
    }

    @NotNull
    private String deviceName() {
        return config.get("deviceName");
    }

    @NotNull
    private Connect.DeviceType deviceType() {
        return config.getEnum("deviceType", Connect.DeviceType.class);
    }

    @NotNull
    private String preferredLocale() {
        return config.get("preferredLocale");
    }

    @NotNull
    private String authUsername() {
        return config.get("auth.username");
    }

    @NotNull
    private String authPassword() {
        return config.get("auth.password");
    }

    @NotNull
    private String authBlob() {
        return config.get("auth.blob");
    }

    @Nullable
    private File credentialsFile() {
        String path = config.get("auth.credentialsFile");
        if (path == null || path.isEmpty()) return null;
        return new File(path);
    }

    @NotNull
    public Level loggingLevel() {
        return Level.toLevel(config.get("logLevel"));
    }

    @NotNull
    public FileConfiguration.AuthStrategy authStrategy() {
        return config.getEnum("auth.strategy", AuthStrategy.class);
    }

    public int apiPort() {
        return config.get("api.port");
    }

    @NotNull
    public String apiHost() {
        return config.get("api.host");
    }

    @NotNull
    public ShellEvents.Configuration toEventsShell() {
        return new ShellEvents.Configuration.Builder()
                .setEnabled(config.get("shell.enabled"))
                .setExecuteWithBash(config.get("shell.executeWithBash"))
                .setOnContextChanged(config.get("shell.onContextChanged"))
                .setOnTrackChanged(config.get("shell.onTrackChanged"))
                .setOnPlaybackEnded(config.get("shell.onPlaybackEnded"))
                .setOnPlaybackPaused(config.get("shell.onPlaybackPaused"))
                .setOnPlaybackResumed(config.get("shell.onPlaybackResumed"))
                .setOnPlaybackFailed(config.get("shell.onPlaybackFailed"))
                .setOnTrackSeeked(config.get("shell.onTrackSeeked"))
                .setOnMetadataAvailable(config.get("shell.onMetadataAvailable"))
                .setOnVolumeChanged(config.get("shell.onVolumeChanged"))
                .setOnInactiveSession(config.get("shell.onInactiveSession"))
                .setOnPanicState(config.get("shell.onPanicState"))
                .setOnConnectionDropped(config.get("shell.onConnectionDropped"))
                .setOnConnectionEstablished(config.get("shell.onConnectionEstablished"))
                .setOnStartedLoading(config.get("shell.onStartedLoading"))
                .setOnFinishedLoading(config.get("shell.onFinishedLoading"))
                .build();
    }

    @NotNull
    public ZeroconfServer.Builder initZeroconfBuilder() {
        ZeroconfServer.Builder builder = new ZeroconfServer.Builder(toSession())
                .setPreferredLocale(preferredLocale())
                .setDeviceType(deviceType())
                .setDeviceName(deviceName())
                .setClientToken(clientToken())
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
                .setClientToken(clientToken())
                .setDeviceId(deviceId());

        switch (authStrategy()) {
            case FACEBOOK:
                builder.facebook();
                break;
            case BLOB:
                builder.blob(authUsername(), Utils.fromBase64(authBlob()));
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
                .setCacheDir(getFile("cache.dir"))
                .setDoCacheCleanUp(config.get("cache.doCleanUp"))
                .setStoreCredentials(config.get("auth.storeCredentials"))
                .setStoredCredentialsFile(credentialsFile())
                .setTimeSynchronizationMethod(config.getEnum("time.synchronizationMethod", TimeProvider.Method.class))
                .setTimeManualCorrection(config.get("time.manualCorrection"))
                .setProxyEnabled(config.get("proxy.enabled"))
                .setProxyType(config.getEnum("proxy.type", Proxy.Type.class))
                .setProxySSL(config.get("proxy.ssl"))
                .setProxyAddress(config.get("proxy.address"))
                .setProxyPort(config.get("proxy.port"))
                .setProxyAuth(config.get("proxy.auth"))
                .setProxyUsername(config.get("proxy.username"))
                .setProxyPassword(config.get("proxy.password"))
                .setRetryOnChunkError(config.get("player.retryOnChunkError"))
                .setConnectionTimeout(config.get("network.connectionTimeout"))
                .build();
    }

    @NotNull
    public PlayerConfiguration toPlayer() {
        return new PlayerConfiguration.Builder()
                .setAutoplayEnabled(config.get("player.autoplayEnabled"))
                .setCrossfadeDuration(config.get("player.crossfadeDuration"))
                .setEnableNormalisation(config.get("player.enableNormalisation"))
                .setUseAlbumGain(config.get("player.useAlbumGain"))
                .setInitialVolume(config.get("player.initialVolume"))
                .setLogAvailableMixers(config.get("player.logAvailableMixers"))
                .setMetadataPipe(metadataPipe())
                .setMixerSearchKeywords(getStringArray("player.mixerSearchKeywords", ';'))
                .setNormalisationPregain(normalisationPregain())
                .setOutput(config.getEnum("player.output", PlayerConfiguration.AudioOutput.class))
                .setOutputClass(config.get("player.outputClass"))
                .setOutputPipe(outputPipe())
                .setPreferredQuality(preferredQuality())
                .setPreloadEnabled(config.get("preload.enabled"))
                .setReleaseLineDelay(config.get("player.releaseLineDelay"))
                .setVolumeSteps(config.get("player.volumeSteps"))
                .setBypassSinkVolume(config.get("player.bypassSinkVolume"))
                .setLocalFilesPath(getFile("player.localFilesPath"))
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
