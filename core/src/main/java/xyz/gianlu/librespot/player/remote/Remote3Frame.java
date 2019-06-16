package xyz.gianlu.librespot.player.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;

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
    public final Remote3Track track;
    public final List<Remote3Track> prevTracks;
    public final List<Remote3Track> nextTracks;

    public Remote3Frame(@NotNull JsonObject obj) {
        playbackId = Utils.optString(obj, "playback_id", null);
        contextUrl = Utils.optString(obj, "context_url", null);
        contextUri = Utils.optString(obj, "context_uri", null);
        sessionId = Utils.optString(obj, "session_id", null);
        queueRevision = Utils.optString(obj, "queue_revision", null);
        entityUrl = Utils.optString(obj, "entity_url", null);
        duration = (int) Utils.optLong(obj, "duration", 0);
        positionAsOfTimestamp = (int) Utils.optLong(obj, "position_as_of_timestamp", 0);
        timestamp = Utils.optLong(obj, "timestamp", 0);
        isPlaying = Utils.optBoolean(obj, "is_playing", false);
        isPaused = Utils.optBoolean(obj, "is_paused", false);
        isSystemInitiated = Utils.optBoolean(obj, "is_system_initiated", false);
        playbackSpeed = (float) Utils.optDouble(obj, "playback_speed", 0);
        endpoint = Endpoint.opt(obj, "endpoint");
        context = Context.opt(obj, "context");
        playOrigin = PlayOrigin.opt(obj, "play_origin");
        playOptions = PlayOptions.opt(obj, "play_options");
        options = Options.opt(obj, "options");
        value = obj.getAsJsonPrimitive("value");
        track = Remote3Track.opt(obj, "track");
        prevTracks = Remote3Track.optArray(obj, "prev_tracks");
        nextTracks = Remote3Track.optArray(obj, "next_tracks");
    }

    public enum Endpoint {
        Play("play"), Pause("pause"), Resume("resume"), SeekTo("seek_to"), SkipNext("skip_next"),
        SkipPrev("skip_prev"), SetShufflingContext("set_shuffling_context"), SetRepeatingContext("set_repeating_context"),
        SetRepeatingTrack("set_repeating_track"), UpdateContext("update_context"), SetQueue("set_queue"),
        AddToQueue("add_to_queue");

        private final String val;

        Endpoint(@NotNull String val) {
            this.val = val;
        }

        @Nullable
        public static Endpoint opt(@NotNull JsonObject obj, @NotNull String key) {
            String str = Utils.optString(obj, key, null);
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
            featureIdentifier = Utils.optString(obj, "feature_identifier", null);
            featureVersion = Utils.optString(obj, "feature_version", null);
            viewUri = Utils.optString(obj, "view_uri", null);
            referrerIdentifier = Utils.optString(obj, "referrer_identifier", null);
            featureClasses = Utils.optStringArray(obj, "feature_classes");
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
                String str = Utils.optString(obj, key, null);
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
                String str = Utils.optString(obj, key, null);
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
                String str = Utils.optString(obj, key, null);
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
        public final Boolean initiallyPaused;
        public final PlayerOptionsOverride playerOptionsOverride;
        public final SkipTo skipTo;
        public final License license;
        public final PrefetchLevel prefetchLevel;
        public final AudioStream audioStream;
        public final JsonObject suppressions;

        private Options(@NotNull JsonObject obj) {
            seekTo = (int) Utils.optLong(obj, "seek_to", 0);
            initiallyPaused = Utils.optBoolean(obj, "initially_paused");
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
                String str = Utils.optString(obj, key, null);
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
                String str = Utils.optString(obj, key, null);
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
                String str = Utils.optString(obj, key, null);
                if (str == null || str.isEmpty()) return null;

                for (AudioStream e : values())
                    if (e.val.equals(str))
                        return e;

                throw new IllegalArgumentException("Unknown audio stream for " + str);
            }
        }

        public static class SkipTo {
            public final int pageIndex;
            public final int trackIndex;
            public final String trackUid;
            public final String trackUri;

            private SkipTo(@NotNull JsonObject obj) {
                pageIndex = (int) Utils.optLong(obj, "page_index", -1);
                trackIndex = (int) Utils.optLong(obj, "track_index", -1);
                trackUid = Utils.optString(obj, "track_uid", null);
                trackUri = Utils.optString(obj, "track_uri", null);
            }

            @Nullable
            public static SkipTo opt(@NotNull JsonObject obj, @NotNull String key) {
                JsonElement elm = obj.get(key);
                if (elm == null || !elm.isJsonObject()) return null;
                return new SkipTo(elm.getAsJsonObject());
            }
        }

        public static class PlayerOptionsOverride {
            public final Boolean shufflingContext;
            public final Boolean repeatingContext;
            public final Boolean repeatingTrack;

            private PlayerOptionsOverride(@NotNull JsonObject obj) {
                shufflingContext = Utils.optBoolean(obj, "shuffling_context");
                repeatingContext = Utils.optBoolean(obj, "repeating_context");
                repeatingTrack = Utils.optBoolean(obj, "repeating_track");
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
        public final String url;
        public final JsonObject metadata;
        public final List<Remote3Page> pages;
        public final Restrictions restrictions;

        private Context(@NotNull JsonObject obj) {
            uri = Utils.optString(obj, "uri", null);
            url = Utils.optString(obj, "url", null);
            metadata = obj.getAsJsonObject("metadata");
            pages = Remote3Page.opt(obj.getAsJsonArray("pages"));
            restrictions = Restrictions.opt(obj, "restrictions");
        }

        @Nullable
        public static Context opt(@NotNull JsonObject obj, @NotNull String key) {
            JsonElement elm = obj.get(key);
            if (elm == null || !elm.isJsonObject()) return null;
            return new Context(elm.getAsJsonObject());
        }

        public static class Restrictions {
            private final JsonObject obj;

            private Restrictions(@NotNull JsonObject obj) {
                this.obj = obj;
            }

            @Nullable
            public static Restrictions opt(@NotNull JsonObject obj, @NotNull String key) {
                JsonElement elm = obj.get(key);
                if (elm == null || !elm.isJsonObject()) return null;
                return new Restrictions(elm.getAsJsonObject());
            }

            public boolean allowed(@NotNull Type type) {
                JsonArray array = obj.getAsJsonArray(type.val);
                if (array == null) return true;
                return array.size() == 0;
            }

            public enum Type {
                PAUSING("disallow_pausing_reasons"), RESUMING("disallow_resuming_reasons"), SEEKING("disallow_seeking_reasons"),
                PEEKING_PREV("disallow_peeking_prev_reasons"), PEEKING_NEXT("disallow_peeking_next_reasons"),
                SKIPPING_PREV("disallow_skipping_prev_reasons"), SKIPPING_NEXT("disallow_skipping_next_reasons"),
                TOGGLING_REPEAT_CONTEXT("disallow_toggling_repeat_context_reasons"), TOGGLING_REPEAT_TRACK("disallow_toggling_repeat_track_reasons"),
                TOGGLING_SHUFFLE("disallow_toggling_shuffle_reasons"), SET_QUEUE("disallow_set_queue_reasons"),
                INTERRUPTING_PLAYBACK("disallow_interrupting_playback_reasons"), TRANSFERRING_PLAYBACK("disallow_transferring_playback_reasons"),
                REMOTE_CONTROL("disallow_remote_control_reasons"), INSERTING_INTO_NEXT_TRACKS("disallow_inserting_into_next_tracks_reasons"),
                INSERTING_INTO_CONTEXT_TRACKS("disallow_inserting_into_context_tracks_reasons"), REORDERING_IN_NEXT_TRACKS("disallow_reordering_in_next_tracks_reasons"),
                REORDERING_IN_CONTEXT_TRACKS("disallow_reordering_in_context_tracks_reasons"), REMOVING_FROM_NEXT_TRACKS("disallow_removing_from_next_tracks_reasons"),
                REMOVING_FROM_CONTEXT_TRACKS("disallow_removing_from_context_tracks_reasons"), UPDATING_CONTEXT("disallow_updating_context_reasons");

                private final String val;

                Type(@NotNull String val) {
                    this.val = val;
                }
            }
        }
    }
}
