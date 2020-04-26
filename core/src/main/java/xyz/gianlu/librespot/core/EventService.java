package xyz.gianlu.librespot.core;

import com.spotify.metadata.Metadata;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.AsyncWorker;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.RawMercuryRequest;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.player.StateWrapper;
import xyz.gianlu.librespot.player.playback.PlayerMetrics;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author Gianlu
 */
public final class EventService implements Closeable {
    private final static Logger LOGGER = Logger.getLogger(EventService.class);
    private final Session session;
    private final AsyncWorker<EventBuilder> asyncWorker;
    private long trackTransitionIncremental = 1;

    EventService(@NotNull Session session) {
        this.session = session;
        this.asyncWorker = new AsyncWorker<>("event-service-sender", eventBuilder -> {
            try {
                byte[] body = eventBuilder.toArray();
                MercuryClient.Response resp = session.mercury().sendSync(RawMercuryRequest.newBuilder()
                        .setUri("hm://event-service/v1/events").setMethod("POST")
                        .addUserField("Accept-Language", "en")
                        .addUserField("X-ClientTimeStamp", String.valueOf(TimeProvider.currentTimeMillis()))
                        .addPayloadPart(body)
                        .build());

                LOGGER.debug(String.format("Event sent. {body: %s, result: %d}", EventBuilder.toString(body), resp.statusCode));
            } catch (IOException ex) {
                LOGGER.error("Failed sending event: " + eventBuilder, ex);
            }
        });
    }

    private void sendEvent(@NotNull EventBuilder builder) {
        asyncWorker.submit(builder);
    }

    /**
     * Reports our language.
     *
     * @param lang The language (2 letters code)
     */
    public void language(@NotNull String lang) {
        EventBuilder event = new EventBuilder(Type.LANGUAGE);
        event.append(lang);
        sendEvent(event);
    }

    private void trackTransition(@NotNull PlaybackMetrics metrics) {
        int when = metrics.lastValue();

        EventBuilder event = new EventBuilder(Type.TRACK_TRANSITION);
        event.append(String.valueOf(trackTransitionIncremental++));
        event.append(session.deviceId());
        event.append(metrics.playbackId).append("00000000000000000000000000000000");
        event.append(metrics.sourceStart).append(metrics.startedHow());
        event.append(metrics.sourceEnd).append(metrics.endedHow());
        event.append(String.valueOf(metrics.player.decodedLength)).append(String.valueOf(metrics.player.size));
        event.append(String.valueOf(when)).append(String.valueOf(when));
        event.append(String.valueOf(metrics.player.duration));
        event.append('0' /* TODO: Encrypt latency */).append(String.valueOf(metrics.player.fadeOverlap)).append('0' /* FIXME */).append('0');
        event.append(metrics.firstValue() == 0 ? '0' : '1').append(String.valueOf(metrics.firstValue()));
        event.append('0' /* TODO: Play latency */).append("-1" /* FIXME */).append("context");
        event.append(String.valueOf(metrics.player.contentMetrics.audioKeyTime)).append('0');
        event.append(metrics.player.contentMetrics.preloadedAudioKey ? '1' : '0').append('0').append('0' /* FIXME */).append('0');
        event.append(String.valueOf(when)).append(String.valueOf(when));
        event.append('0').append(String.valueOf(metrics.player.bitrate));
        event.append(metrics.contextUri).append(metrics.player.encoding);
        event.append(metrics.id.hexId()).append("");
        event.append('0').append(String.valueOf(metrics.timestamp)).append('0');
        event.append("context").append(metrics.referrerIdentifier).append(metrics.featureVersion);
        event.append("com.spotify").append(metrics.player.transition).append("none").append("local").append("na").append("none");
        sendEvent(event);
    }

    public void trackPlayed(@NotNull PlaybackMetrics metrics) {
        if (metrics.player == null || metrics.player.contentMetrics == null) {
            LOGGER.warn("Did not send event because of missing metrics: " + metrics.playbackId);
            return;
        }

        trackTransition(metrics);

        EventBuilder event = new EventBuilder(Type.TRACK_PLAYED);
        event.append(metrics.playbackId).append(metrics.id.toSpotifyUri());
        event.append('0').append(metrics.intervalsToSend());
        sendEvent(event);
    }

    /**
     * Reports that a new playback ID is being used.
     *
     * @param state      The current player state
     * @param playbackId The new playback ID
     */
    public void newPlaybackId(@NotNull StateWrapper state, @NotNull String playbackId) {
        EventBuilder event = new EventBuilder(Type.NEW_PLAYBACK_ID);
        event.append(playbackId).append(state.getSessionId()).append(String.valueOf(TimeProvider.currentTimeMillis()));
        sendEvent(event);
    }

    /**
     * Reports that a new session ID is being used.
     *
     * @param sessionId The session ID
     * @param state     The current player state
     */
    public void newSessionId(@NotNull String sessionId, @NotNull StateWrapper state) {
        String contextUri = state.getContextUri();

        EventBuilder event = new EventBuilder(Type.NEW_SESSION_ID);
        event.append(sessionId);
        event.append(contextUri);
        event.append(contextUri);
        event.append(String.valueOf(TimeProvider.currentTimeMillis()));
        event.append("").append(String.valueOf(state.getContextSize()));
        event.append(state.getContextUrl());
        sendEvent(event);
    }

    /**
     * Reports that a file ID has been fetched for some content.
     *
     * @param id   The content {@link PlayableId}
     * @param file The {@link com.spotify.metadata.Metadata.AudioFile} for this content
     */
    public void fetchedFileId(@NotNull PlayableId id, @NotNull Metadata.AudioFile file) {
        EventBuilder event = new EventBuilder(Type.FETCHED_FILE_ID);
        event.append('2').append('2');
        event.append(Utils.bytesToHex(file.getFileId()).toLowerCase());
        event.append(id.toSpotifyUri());
        event.append('1').append('2').append('2'); // FIXME
        sendEvent(event);
    }

    @Override
    public void close() {
        asyncWorker.close();

        try {
            asyncWorker.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    private enum Type {
        LANGUAGE("812", "1"), FETCHED_FILE_ID("274", "3"), NEW_SESSION_ID("557", "3"),
        NEW_PLAYBACK_ID("558", "1"), TRACK_PLAYED("372", "1"), TRACK_TRANSITION("12", "37");

        private final String id;
        private final String unknown;

        Type(@NotNull String id, @NotNull String unknown) {
            this.id = id;
            this.unknown = unknown;
        }
    }

    private static class EventBuilder {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream(256);

        EventBuilder(@NotNull Type type) {
            appendNoDelimiter(type.id);
            append(type.unknown);
        }

        @NotNull
        static String toString(byte[] body) {
            StringBuilder result = new StringBuilder();
            for (byte b : body) {
                if (b == 0x09) result.append('|');
                else result.append((char) b);
            }

            return result.toString();
        }

        private void appendNoDelimiter(@Nullable String str) {
            if (str == null) str = "";

            try {
                body.write(str.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @NotNull
        EventBuilder append(char c) {
            body.write(0x09);
            body.write(c);
            return this;
        }

        @NotNull
        EventBuilder append(@Nullable String str) {
            body.write(0x09);
            appendNoDelimiter(str);
            return this;
        }

        @Override
        public String toString() {
            return "EventBuilder{" + toString(toArray()) + '}';
        }

        @NotNull
        byte[] toArray() {
            return body.toByteArray();
        }
    }

    public static class PlaybackMetrics {
        public final PlayableId id;
        final List<Interval> intervals = new ArrayList<>(10);
        final String playbackId;
        final String featureVersion;
        final String referrerIdentifier;
        final String contextUri;
        final long timestamp;
        PlayerMetrics player = null;
        Reason reasonStart = null;
        String sourceStart = null;
        Reason reasonEnd = null;
        String sourceEnd = null;
        Interval lastInterval = null;

        public PlaybackMetrics(@NotNull PlayableId id, @NotNull String playbackId, @NotNull StateWrapper state) {
            this.id = id;
            this.playbackId = playbackId;
            this.contextUri = state.getContextUri();
            this.featureVersion = state.getPlayOrigin().getFeatureVersion();
            this.referrerIdentifier = state.getPlayOrigin().getReferrerIdentifier();
            this.timestamp = TimeProvider.currentTimeMillis();
        }

        @NotNull
        String intervalsToSend() {
            StringBuilder builder = new StringBuilder();
            builder.append('[');

            boolean first = true;
            for (Interval interval : intervals) {
                if (interval.begin == -1 || interval.end == -1)
                    continue;

                if (!first) builder.append(',');
                builder.append('[').append(interval.begin).append(',').append(interval.end).append(']');
                first = false;
            }

            builder.append(']');
            return builder.toString();
        }

        int firstValue() {
            if (intervals.isEmpty()) return 0;
            else return intervals.get(0).begin;
        }

        int lastValue() {
            if (intervals.isEmpty()) return player == null ? 0 : player.duration;
            else return intervals.get(intervals.size() - 1).end;
        }

        public void startInterval(int begin) {
            lastInterval = new Interval(begin);
        }

        public void endInterval(int end) {
            if (lastInterval == null) return;
            if (lastInterval.begin == end) {
                lastInterval = null;
                return;
            }

            lastInterval.end = end;
            intervals.add(lastInterval);
            lastInterval = null;
        }

        public void startedHow(@NotNull EventService.PlaybackMetrics.Reason reason, @Nullable String origin) {
            reasonStart = reason;
            sourceStart = origin == null ? "unknown" : origin;
        }

        public void endedHow(@NotNull EventService.PlaybackMetrics.Reason reason, @Nullable String origin) {
            reasonEnd = reason;
            sourceEnd = origin == null ? "unknown" : origin;
        }

        @Nullable
        String startedHow() {
            return reasonStart == null ? null : reasonStart.val;
        }

        @Nullable
        String endedHow() {
            return reasonEnd == null ? null : reasonEnd.val;
        }

        public void update(@Nullable PlayerMetrics playerMetrics) {
            player = playerMetrics;
        }

        public enum Reason {
            TRACK_DONE("trackdone"), TRACK_ERROR("trackerror"), FORWARD_BTN("fwdbtn"), BACK_BTN("backbtn"),
            END_PLAY("endplay"), PLAY_BTN("playbtn"), CLICK_ROW("clickrow"), LOGOUT("logout"), APP_LOAD("appload");

            final String val;

            Reason(@NotNull String val) {
                this.val = val;
            }
        }

        private static class Interval {
            private final int begin;
            private int end = -1;

            Interval(int begin) {
                this.begin = begin;
            }
        }
    }
}
