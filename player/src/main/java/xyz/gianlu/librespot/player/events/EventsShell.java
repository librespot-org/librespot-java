package xyz.gianlu.librespot.player.events;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.metadata.PlayableId;
import xyz.gianlu.librespot.player.Player;
import xyz.gianlu.librespot.player.TrackOrEpisode;

import java.io.IOException;

/**
 * @author devgianlu
 */
public final class EventsShell implements Player.EventsListener, Session.@NotNull ReconnectionListener {
    private static final Logger LOGGER = LogManager.getLogger(EventsShell.class);
    private final Configuration conf;
    private final Runtime runtime;

    public EventsShell(@NotNull Configuration conf) {
        this.runtime = Runtime.getRuntime();
        this.conf = conf;
    }

    private void exec(String command) {
        if (!this.conf.enabled)
            return;

        if (command == null || command.trim().isEmpty())
            return;

        try {
            int exitCode = runtime.exec(command.trim()).waitFor();
            LOGGER.trace("Executed shell command: {} -> {}", command, exitCode);
        } catch (IOException | InterruptedException ex) {
            LOGGER.error("Failed executing command: {}", command, ex);
        }
    }

    @Override
    public void onContextChanged(@NotNull Player player, @NotNull String newUri) {
        exec(conf.onContextChanged);
    }

    @Override
    public void onTrackChanged(@NotNull Player player, @NotNull PlayableId id, @Nullable TrackOrEpisode metadata) {
        exec(conf.onTrackChanged);
    }

    @Override
    public void onPlaybackEnded(@NotNull Player player) {
        exec(conf.onPlaybackEnded);
    }

    @Override
    public void onPlaybackPaused(@NotNull Player player, long trackTime) {
        exec(conf.onPlaybackPaused);
    }

    @Override
    public void onPlaybackResumed(@NotNull Player player, long trackTime) {
        exec(conf.onPlaybackResumed);
    }

    @Override
    public void onTrackSeeked(@NotNull Player player, long trackTime) {
        exec(conf.onTrackSeeked);
    }

    @Override
    public void onMetadataAvailable(@NotNull Player player, @NotNull TrackOrEpisode metadata) {
        exec(conf.onMetadataAvailable);
    }

    @Override
    public void onPlaybackHaltStateChanged(@NotNull Player player, boolean halted, long trackTime) {
    }

    @Override
    public void onInactiveSession(@NotNull Player player, boolean timeout) {
        exec(conf.onInactiveSession);
    }

    @Override
    public void onVolumeChanged(@NotNull Player player, @Range(from = 0, to = 1) float volume) {
        exec(conf.onVolumeChanged);
    }

    @Override
    public void onPanicState(@NotNull Player player) {
        exec(conf.onPanicState);
    }

    @Override
    public void onConnectionDropped() {
        exec(conf.onConnectionDropped);
    }

    @Override
    public void onConnectionEstablished() {
        exec(conf.onConnectionEstablished);
    }

    public static class Configuration {
        public final boolean enabled;
        public final String onContextChanged;
        public final String onTrackChanged;
        public final String onPlaybackEnded;
        public final String onPlaybackPaused;
        public final String onPlaybackResumed;
        public final String onTrackSeeked;
        public final String onMetadataAvailable;
        public final String onVolumeChanged;
        public final String onInactiveSession;
        public final String onPanicState;
        public final String onConnectionDropped;
        public final String onConnectionEstablished;

        public Configuration(boolean enabled, String onContextChanged, String onTrackChanged, String onPlaybackEnded, String onPlaybackPaused,
                             String onPlaybackResumed, String onTrackSeeked, String onMetadataAvailable, String onVolumeChanged,
                             String onInactiveSession, String onPanicState, String onConnectionDropped, String onConnectionEstablished) {
            this.enabled = enabled;
            this.onContextChanged = onContextChanged;
            this.onTrackChanged = onTrackChanged;
            this.onPlaybackEnded = onPlaybackEnded;
            this.onPlaybackPaused = onPlaybackPaused;
            this.onPlaybackResumed = onPlaybackResumed;
            this.onTrackSeeked = onTrackSeeked;
            this.onMetadataAvailable = onMetadataAvailable;
            this.onVolumeChanged = onVolumeChanged;
            this.onInactiveSession = onInactiveSession;
            this.onPanicState = onPanicState;
            this.onConnectionDropped = onConnectionDropped;
            this.onConnectionEstablished = onConnectionEstablished;
        }

        public static class Builder {
            private boolean enabled = false;
            private String onContextChanged = "";
            private String onTrackChanged = "";
            private String onPlaybackEnded = "";
            private String onPlaybackPaused = "";
            private String onPlaybackResumed = "";
            private String onTrackSeeked = "";
            private String onMetadataAvailable = "";
            private String onVolumeChanged = "";
            private String onInactiveSession = "";
            private String onPanicState = "";
            private String onConnectionDropped = "";
            private String onConnectionEstablished = "";

            public Builder() {
            }

            public Builder setEnabled(boolean enabled) {
                this.enabled = enabled;
                return this;
            }

            public Builder setOnContextChanged(String command) {
                this.onContextChanged = command;
                return this;
            }

            public Builder setOnTrackChanged(String command) {
                this.onTrackChanged = command;
                return this;
            }

            public Builder setOnPlaybackEnded(String command) {
                this.onPlaybackEnded = command;
                return this;
            }

            public Builder setOnPlaybackPaused(String command) {
                this.onPlaybackPaused = command;
                return this;
            }

            public Builder setOnPlaybackResumed(String command) {
                this.onPlaybackResumed = command;
                return this;
            }

            public Builder setOnTrackSeeked(String command) {
                this.onTrackSeeked = command;
                return this;
            }

            public Builder setOnMetadataAvailable(String command) {
                this.onMetadataAvailable = command;
                return this;
            }

            public Builder setOnVolumeChanged(String command) {
                this.onVolumeChanged = command;
                return this;
            }

            public Builder setOnInactiveSession(String command) {
                this.onInactiveSession = command;
                return this;
            }

            public Builder setOnPanicState(String command) {
                this.onPanicState = command;
                return this;
            }

            public Builder setOnConnectionDropped(String command) {
                this.onConnectionDropped = command;
                return this;
            }

            public Builder setOnConnectionEstablished(String command) {
                this.onConnectionEstablished = command;
                return this;
            }

            @NotNull
            public Configuration build() {
                return new Configuration(enabled, onContextChanged, onTrackChanged, onPlaybackEnded, onPlaybackPaused, onPlaybackResumed,
                        onTrackSeeked, onMetadataAvailable, onVolumeChanged, onInactiveSession, onPanicState, onConnectionDropped, onConnectionEstablished);
            }
        }
    }
}
