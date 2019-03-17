package xyz.gianlu.librespot.player.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.mercury.model.TrackId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * @author Gianlu
 */
public class Remote3Frame {
    public final String playbackId;
    public final String contextUrl;
    public final float playbackSpeed;
    public final long timestamp;
    public final String contextUri;
    public final int duration;
    public final boolean isPlaying;
    public final boolean isPaused;
    public final boolean isSystemInitiated;
    public final int positionAsOfTimestamp;
    public final String sessionId;
    public final String queueRevision;
    public final String entityUrl;
    public final Endpoint endpoint;
    public final Context context;
    public final PlayOrigin playOrigin;
    public final PlayOptions playOptions;
    public final Options options;
    public final JsonPrimitive value;
    public final Track track;

    public Remote3Frame(@NotNull JsonObject obj) {
        playbackId = optString(obj, "playback_id", null);
        contextUrl = optString(obj, "context_url", null);
        contextUri = optString(obj, "context_uri", null);
        sessionId = optString(obj, "session_id", null);
        queueRevision = optString(obj, "queue_revision", null);
        entityUrl = optString(obj, "entity_url", null);
        duration = (int) optLong(obj, "duration", 0);
        positionAsOfTimestamp = (int) optLong(obj, "position_as_of_timestamp", 0);
        timestamp = optLong(obj, "timestamp", 0);
        isPlaying = optBoolean(obj, "is_playing", false);
        isPaused = optBoolean(obj, "is_paused", false);
        isSystemInitiated = optBoolean(obj, "is_system_initiated", false);
        playbackSpeed = (float) optDouble(obj, "playback_speed", 0);
        endpoint = Endpoint.opt(obj, "endpoint");
        context = Context.opt(obj, "context");
        playOrigin = PlayOrigin.opt(obj, "play_origin");
        playOptions = PlayOptions.opt(obj, "play_options");
        options = Options.opt(obj, "options");
        value = obj.getAsJsonPrimitive("value");
        track = Track.opt(obj, "track");
    }

    @Contract("_, _, !null -> !null")
    private static String optString(@NotNull JsonObject obj, @NotNull String key, @Nullable String fallback) {
        JsonElement elm = obj.get(key);
        if (elm == null || !elm.getAsJsonPrimitive().isString()) return fallback;
        return elm.getAsString();
    }

    private static long optLong(@NotNull JsonObject obj, @NotNull String key, long fallback) {
        JsonElement elm = obj.get(key);
        if (elm == null || !elm.getAsJsonPrimitive().isNumber()) return fallback;
        return elm.getAsLong();
    }

    private static boolean optBoolean(@NotNull JsonObject obj, @NotNull String key, boolean fallback) {
        JsonElement elm = obj.get(key);
        if (elm == null || !elm.getAsJsonPrimitive().isBoolean()) return fallback;
        return elm.getAsBoolean();
    }

    private static double optDouble(@NotNull JsonObject obj, @NotNull String key, double fallback) {
        JsonElement elm = obj.get(key);
        if (elm == null || !elm.getAsJsonPrimitive().isNumber()) return fallback;
        return elm.getAsDouble();
    }

    @Nullable
    private static String[] optStringArray(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement elm = obj.get(key);
        if (elm == null || !elm.isJsonArray()) return null;

        JsonArray a = elm.getAsJsonArray();
        String[] str = new String[a.size()];
        for (int i = 0; i < a.size(); i++)
            str[i] = a.get(i).getAsString();

        return str;
    }

    public enum Endpoint {
        Play("play"),
        Pause("pause"),
        Resume("resume"),
        SeekTo("seek_to"),
        SkipNext("skip_next"),
        SkipPrev("skip_prev"),
        SetShufflingContext("set_shuffling_context"),
        SetRepeatingContext("set_repeating_context"),
        SetRepeatingTrack("set_repeating_track"),
        UpdateContext("update_context");

        private final String val;

        Endpoint(@NotNull String val) {
            this.val = val;
        }

        @Nullable
        public static Endpoint opt(@NotNull JsonObject obj, @NotNull String key) {
            String str = optString(obj, key, null);
            if (str == null || str.isEmpty()) return null;

            for (Endpoint e : values())
                if (e.val.equals(str))
                    return e;

            throw new IllegalArgumentException("Unknown endpoint for " + str);
        }
    }

    public static class PlayOrigin {
        public final String featureIdentifier;
        public final String featureVersion;
        public final String viewUri;
        public final String referrerIdentifier;
        public final String[] featureClasses;

        private PlayOrigin(@NotNull JsonObject obj) {
            featureIdentifier = optString(obj, "feature_identifier", null);
            featureVersion = optString(obj, "feature_version", null);
            viewUri = optString(obj, "view_uri", null);
            referrerIdentifier = optString(obj, "referrer_identifier", null);
            featureClasses = optStringArray(obj, "feature_classes");
        }

        @Nullable
        public static PlayOrigin opt(@NotNull JsonObject obj, @NotNull String key) {
            JsonElement elm = obj.get(key);
            if (elm == null || !elm.isJsonObject()) return null;
            return new PlayOrigin(elm.getAsJsonObject());
        }
    }

    public static class PlayOptions {
        public final Operation operation;
        public final Trigger trigger;
        public final Reason reason;

        private PlayOptions(@NotNull JsonObject obj) {
            operation = Operation.opt(obj, "operation");
            trigger = Trigger.opt(obj, "trigger");
            reason = Reason.opt(obj, "reason");
        }

        @Nullable
        public static PlayOptions opt(@NotNull JsonObject obj, @NotNull String key) {
            JsonElement elm = obj.get(key);
            if (elm == null || !elm.isJsonObject()) return null;
            return new PlayOptions(elm.getAsJsonObject());
        }

        public enum Operation {
            Replace("replace");

            private final String val;

            Operation(@NotNull String val) {
                this.val = val;
            }

            @Nullable
            public static Operation opt(@NotNull JsonObject obj, @NotNull String key) {
                String str = optString(obj, key, null);
                if (str == null || str.isEmpty()) return null;

                for (Operation e : values())
                    if (e.val.equals(str))
                        return e;

                throw new IllegalArgumentException("Unknown operation for " + str);
            }
        }

        public enum Trigger {
            Immediately("immediately");

            private final String val;

            Trigger(@NotNull String val) {
                this.val = val;
            }

            @Nullable
            public static Trigger opt(@NotNull JsonObject obj, @NotNull String key) {
                String str = optString(obj, key, null);
                if (str == null || str.isEmpty()) return null;

                for (Trigger e : values())
                    if (e.val.equals(str))
                        return e;

                throw new IllegalArgumentException("Unknown trigger for " + str);
            }
        }

        public enum Reason {
            Interactive("interactive");

            private final String val;

            Reason(@NotNull String val) {
                this.val = val;
            }

            @Nullable
            public static Reason opt(@NotNull JsonObject obj, @NotNull String key) {
                String str = optString(obj, key, null);
                if (str == null || str.isEmpty()) return null;

                for (Reason e : values())
                    if (e.val.equals(str))
                        return e;

                throw new IllegalArgumentException("Unknown reason for " + str);
            }
        }
    }

    public static class Options {
        public final int seekTo;
        public final boolean initiallyPaused;
        public final PlayerOptionsOverride playerOptionsOverride;
        public final SkipTo skipTo;
        public final License license;
        public final PrefetchLevel prefetchLevel;
        public final AudioStream audioStream;
        public final JsonObject suppressions;

        private Options(@NotNull JsonObject obj) {
            seekTo = (int) optLong(obj, "seek_to", 0);
            initiallyPaused = optBoolean(obj, "initially_paused", false);
            playerOptionsOverride = PlayerOptionsOverride.opt(obj, "player_options_override");
            skipTo = SkipTo.opt(obj, "skip_to");
            license = License.opt(obj, "license");
            prefetchLevel = PrefetchLevel.opt(obj, "prefetch_level");
            audioStream = AudioStream.opt(obj, "audio_stream");
            suppressions = obj.getAsJsonObject("suppressions");
        }

        @Nullable
        public static Options opt(@NotNull JsonObject obj, @NotNull String key) {
            JsonElement elm = obj.get(key);
            if (elm == null || !elm.isJsonObject()) return null;
            return new Options(elm.getAsJsonObject());
        }

        public enum License {
            Free("free"),
            Premium("premium");

            private final String val;

            License(@NotNull String val) {
                this.val = val;
            }

            @Nullable
            public static License opt(@NotNull JsonObject obj, @NotNull String key) {
                String str = optString(obj, key, null);
                if (str == null || str.isEmpty()) return null;

                for (License e : values())
                    if (e.val.equals(str))
                        return e;

                throw new IllegalArgumentException("Unknown license for " + str);
            }
        }

        public enum PrefetchLevel {
            None("none");

            private final String val;

            PrefetchLevel(@NotNull String val) {
                this.val = val;
            }

            @Nullable
            public static PrefetchLevel opt(@NotNull JsonObject obj, @NotNull String key) {
                String str = optString(obj, key, null);
                if (str == null || str.isEmpty()) return null;

                for (PrefetchLevel e : values())
                    if (e.val.equals(str))
                        return e;

                throw new IllegalArgumentException("Unknown prefetch level for " + str);
            }
        }

        public enum AudioStream {
            Default("default");

            private final String val;

            AudioStream(@NotNull String val) {
                this.val = val;
            }

            @Nullable
            public static AudioStream opt(@NotNull JsonObject obj, @NotNull String key) {
                String str = optString(obj, key, null);
                if (str == null || str.isEmpty()) return null;

                for (AudioStream e : values())
                    if (e.val.equals(str))
                        return e;

                throw new IllegalArgumentException("Unknown audio stream for " + str);
            }
        }

        public static class SkipTo {
            public final int pageIndex;
            public final String trackUid;

            private SkipTo(@NotNull JsonObject obj) {
                pageIndex = (int) optLong(obj, "page_index", -1);
                trackUid = optString(obj, "track_uid", null);
            }

            @Nullable
            public static SkipTo opt(@NotNull JsonObject obj, @NotNull String key) {
                JsonElement elm = obj.get(key);
                if (elm == null || !elm.isJsonObject()) return null;
                return new SkipTo(elm.getAsJsonObject());
            }
        }

        public static class PlayerOptionsOverride {
            public final boolean shufflingContext;
            public final boolean repeatingContext;
            public final boolean repeatingTrack;

            private PlayerOptionsOverride(@NotNull JsonObject obj) {
                shufflingContext = optBoolean(obj, "shuffling_context", false);
                repeatingContext = optBoolean(obj, "repeating_context", false);
                repeatingTrack = optBoolean(obj, "repeating_track", false);
            }

            @Nullable
            public static PlayerOptionsOverride opt(@NotNull JsonObject obj, @NotNull String key) {
                JsonElement elm = obj.get(key);
                if (elm == null || !elm.isJsonObject()) return null;
                return new PlayerOptionsOverride(elm.getAsJsonObject());
            }
        }
    }

    public static class Context {
        public final String uri;
        public final Metadata metadata;
        public final List<Page> pages;

        private Context(@NotNull JsonObject obj) {
            uri = optString(obj, "uri", null);
            metadata = Metadata.opt(obj, "metadata");

            JsonArray pagesArray = obj.getAsJsonArray("pages");
            pages = new ArrayList<>(pagesArray.size());
            for (JsonElement elm : pagesArray)
                pages.add(new Page(elm.getAsJsonObject()));
        }

        @Nullable
        public static Context opt(@NotNull JsonObject obj, @NotNull String key) {
            JsonElement elm = obj.get(key);
            if (elm == null || !elm.isJsonObject()) return null;
            return new Context(elm.getAsJsonObject());
        }

        public static class Page {
            public final List<Track> tracks;

            private Page(@NotNull JsonObject obj) {
                JsonArray array = obj.getAsJsonArray("tracks");
                tracks = new ArrayList<>(array.size());
                for (JsonElement elm : array)
                    tracks.add(new Track(elm.getAsJsonObject()));
            }
        }

        public static class Metadata extends HashMap<String, String> {
            public static final String TRACK_COUNT = "track_count";
            public static final String ZELDA_CONTEXT_URI = "zelda.context_uri";

            private Metadata(@NotNull JsonObject obj) {
                for (String key : obj.keySet())
                    put(key, obj.get(key).getAsString());
            }

            @Nullable
            public static Metadata opt(@NotNull JsonObject obj, @NotNull String key) {
                JsonElement elm = obj.get(key);
                if (elm == null || !elm.isJsonObject()) return null;
                return new Metadata(elm.getAsJsonObject());
            }
        }
    }

    public static class Track {
        public final String uri;
        public final String uid;
        public final JsonObject metadata;
        private TrackId id;

        Track(@NotNull JsonObject obj) {
            uri = optString(obj, "uri", null);
            uid = optString(obj, "uid", null);
            metadata = obj.getAsJsonObject("metadata");
        }

        @Nullable
        public static Track opt(@NotNull JsonObject obj, @NotNull String key) {
            JsonElement elm = obj.get(key);
            if (elm == null || !elm.isJsonObject()) return null;
            return new Track(elm.getAsJsonObject());
        }

        @NotNull
        public TrackId id() {
            if (id == null) id = TrackId.fromUri(uri);
            return id;
        }
    }
}
