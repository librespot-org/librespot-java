package xyz.gianlu.librespot.core;

import com.spotify.connectstate.Player;
import com.spotify.metadata.Metadata;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.AsyncWorker;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.RawMercuryRequest;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.player.PlayerRunner;
import xyz.gianlu.librespot.player.StateWrapper;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
        this.asyncWorker = new AsyncWorker<>("event-service", eventBuilder -> {
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

    public void reportLang(@NotNull String lang) {
        EventBuilder event = new EventBuilder(Type.LANGUAGE);
        event.append(lang);
        sendEvent(event);
    }

    public void trackTransition(@NotNull StateWrapper state, @NotNull EventService.PlaybackMetrics metrics) {
        Player.PlayOrigin playOrigin = state.getPlayOrigin();
        int when = metrics.lastValue();

        EventBuilder event = new EventBuilder(Type.TRACK_TRANSITION);
        event.append(String.valueOf(trackTransitionIncremental++));
        event.append(session.deviceId());
        event.append(state.getPlaybackId()).append("00000000000000000000000000000000");
        event.append(metrics.sourceStart).append(metrics.startedHow());
        event.append(metrics.sourceEnd).append(metrics.endedHow());
        event.append(String.valueOf(metrics.player.decodedLength)).append(String.valueOf(metrics.player.size));
        event.append(String.valueOf(when)).append(String.valueOf(when));
        event.append(String.valueOf(metrics.player.duration));
        event.append('0' /* TODO: Encrypt latency */).append('0' /* TODO: Total fade */).append('0' /* FIXME */).append('0');
        event.append(metrics.firstValue() == 0 ? '0' : '1').append(String.valueOf(metrics.firstValue()));
        event.append('0' /* TODO: Play latency */).append("-1" /* FIXME */).append("context");
        event.append("-1" /* TODO: Audio key sync time */).append('0').append('0' /* TODO: Prefetched audio key */).append('0').append('0' /* FIXME */).append('0');
        event.append(String.valueOf(when)).append(String.valueOf(when));
        event.append('0').append(String.valueOf(metrics.player.bitrate));
        event.append(state.getContextUri()).append(metrics.player.encoding);
        event.append(metrics.id.hexId()).append("");
        event.append('0').append(String.valueOf(TimeProvider.currentTimeMillis())).append('0');
        event.append("context").append(playOrigin.getReferrerIdentifier()).append(playOrigin.getFeatureVersion());
        event.append("com.spotify").append("none" /* TODO: Transition */).append("none").append("local").append("na").append("none");
        sendEvent(event);
    }

    public void trackPlayed(@NotNull StateWrapper state, @NotNull EventService.PlaybackMetrics metrics) {
        if (metrics.player == null)
            return;

        trackTransition(state, metrics);

        EventBuilder event = new EventBuilder(Type.TRACK_PLAYED);
        event.append(state.getPlaybackId()).append(metrics.id.toSpotifyUri());
        event.append('0').append(metrics.intervalsToSend());
        sendEvent(event);
    }

    public void newPlaybackId(@NotNull StateWrapper state) {
        EventBuilder event = new EventBuilder(Type.NEW_PLAYBACK_ID);
        event.append(state.getPlaybackId()).append(state.getSessionId()).append(String.valueOf(TimeProvider.currentTimeMillis()));
        sendEvent(event);
    }

    public void newSessionId(@NotNull StateWrapper state) {
        EventBuilder event = new EventBuilder(Type.NEW_SESSION_ID);
        event.append(state.getSessionId());
        String contextUri = state.getContextUri();
        event.append(contextUri);
        event.append(contextUri);
        event.append(String.valueOf(TimeProvider.currentTimeMillis()));
        event.append("").append(String.valueOf(state.getContextSize()));
        event.append(state.getContextUrl());
        sendEvent(event);
    }

    public void fetchedFileId(@NotNull Metadata.AudioFile file, @NotNull PlayableId id) {
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
        PlayerRunner.PlayerMetrics player = null;
        Interval lastInterval = null;
        Reason reasonStart = null;
        String sourceStart = null;
        Reason reasonEnd = null;
        String sourceEnd = null;

        public PlaybackMetrics(@NotNull PlayableId id) {
            this.id = id;
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

        public void update(@NotNull PlayerRunner.PlayerMetrics playerMetrics) {
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
