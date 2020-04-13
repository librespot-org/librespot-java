package xyz.gianlu.librespot.core;

import com.spotify.metadata.Metadata;
import okhttp3.HttpUrl;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.AsyncWorker;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.RawMercuryRequest;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.player.StateWrapper;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * @author Gianlu
 */
public final class EventService implements Closeable {
    private final static Logger LOGGER = Logger.getLogger(EventService.class);
    private final Session session;
    private final AsyncWorker<EventBuilder> asyncWorker;

    EventService(@NotNull Session session) {
        this.session = session;
        this.asyncWorker = new AsyncWorker<>("event-service", eventBuilder -> {
            try {
                sendEvent(eventBuilder);
            } catch (IOException ex) {
                LOGGER.error("Failed sending event: " + eventBuilder, ex);
            }
        });
    }

    private void sendEvent(@NotNull EventBuilder builder) throws IOException {
        byte[] body = builder.toArray();
        MercuryClient.Response resp = session.mercury().sendSync(RawMercuryRequest.newBuilder()
                .setUri("hm://event-service/v1/events").setMethod("POST")
                .addUserField("Accept-Language", "en")
                .addUserField("X-ClientTimeStamp", String.valueOf(TimeProvider.currentTimeMillis()))
                .addPayloadPart(body)
                .build());

        LOGGER.debug(String.format("Event sent. {body: %s, result: %d}", EventBuilder.toString(body), resp.statusCode));
    }

    public void reportLang(@NotNull String lang) throws IOException {
        EventBuilder event = new EventBuilder(Type.LANGUAGE);
        event.append(lang);
        sendEvent(event);
    }

    public void trackTransition(@NotNull StateWrapper state, PlayableId id) throws IOException {
        EventBuilder event = new EventBuilder(Type.TRACK_TRANSITION);
        event.append("1"); // FIXME: Incremental
        event.append(session.deviceId());
        event.append(state.getPlaybackId());
        event.append("00000000000000000000000000000000");
        event.append("library-collection").append("trackdone");
        event.append("library-collection").append("trackdone");
        event.append("3172204").append("3172204"); // FIXME
        event.append("167548").append("167548").append("167548"); // FIXME
        event.append('8' /* FIXME */).append('0').append('0').append('0').append('0').append('0');
        event.append("12" /* FIXME */).append("-1").append("context").append("-1").append('0').append("1");
        event.append('0').append("72" /* FIXME */).append('0');
        event.append("167548").append("167548"); // FIXME
        event.append('0').append("160000");
        event.append(state.getContextUri()).append("vorbis" /* FIXME */);
        event.append(id.hexId()).append("");
        event.append('0').append(String.valueOf(TimeProvider.currentTimeMillis())).append('0');
        event.append("context").append("spotify:app:collection-songs" /* FIXME */).append("1.1.26" /* FIXME */);
        event.append("com.spotify").append("none").append("none").append("local").append("na").append("none");
        sendEvent(event);
    }

    public void trackPlayed(@NotNull StateWrapper state, @NotNull PlayableId uri, @NotNull PlaybackIntervals intervals) throws IOException {
        trackTransition(state, uri);

        EventBuilder event = new EventBuilder(Type.TRACK_PLAYED);
        event.append(state.getPlaybackId()).append(uri.toSpotifyUri());
        event.append('0').append(intervals.toSend());
        sendEvent(event);
    }

    public void newPlaybackId(@NotNull StateWrapper state) throws IOException {
        EventBuilder event = new EventBuilder(Type.NEW_PLAYBACK_ID);
        event.append(state.getPlaybackId()).append(state.getSessionId()).append(String.valueOf(TimeProvider.currentTimeMillis()));
        sendEvent(event);
    }

    public void newSessionId(@NotNull StateWrapper state) throws IOException {
        EventBuilder event = new EventBuilder(Type.NEW_SESSION_ID);
        event.append(state.getSessionId());
        String contextUri = state.getContextUri();
        event.append(contextUri);
        event.append(contextUri);
        event.append(String.valueOf(TimeProvider.currentTimeMillis()));
        event.append("").append(String.valueOf(state.getContextSize()));
        event.append("context://" + state.getContextUri()); // FIXME
        sendEvent(event);
    }

    public void fetchedFileId(Metadata.AudioFile file, PlayableId id) throws IOException {
        EventBuilder event = new EventBuilder(Type.FETCHED_FILE_ID);
        event.append('2').append('2');
        event.append(Utils.bytesToHex(file.getFileId()).toLowerCase());
        event.append(id.toSpotifyUri());
        event.append('1').append('2').append('2'); // FIXME
        sendEvent(event);
    }

    public void cdnRequest(Metadata.AudioFile file, int fileLength, HttpUrl url) throws IOException { // FIXME
        EventBuilder event = new EventBuilder(Type.CDN_REQUEST);
        event.append(Utils.bytesToHex(file.getFileId()).toLowerCase());
        event.append("00000000000000000000000000000000");
        event.append('0').append('0').append('0').append('0');
        event.append(String.valueOf(fileLength));
        event.append('0').append('0');
        event.append(String.valueOf(fileLength));
        event.append("music");
        event.append("-1").append("-1").append("-1").append("-1.000000").append("-1").append("-1.000000");
        event.append("181").append("181").append("181").append("181.000000").append("181");
        event.append("227").append("227").append("227").append("227.000000").append("227");
        event.append("3427443.636364").append("2914058.000000");
        event.append(url.scheme());
        event.append(url.host());
        event.append("unknown");
        event.append('0').append('0').append('0').append('0');
        event.append(String.valueOf(fileLength));
        event.append("interactive");
        event.append("1345").append("160000").append("1").append('0');
        sendEvent(event);
    }

    @Override
    public void close() {
        asyncWorker.close();
    }

    private enum Type {
        LANGUAGE("812", "1"), CDN_REQUEST("10", "20"), FETCHED_FILE_ID("274", "3"),
        NEW_SESSION_ID("557", "3"), NEW_PLAYBACK_ID("558", "1"), TRACK_PLAYED("372", "1"),
        TRACK_TRANSITION("12", "37");

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
            append(type.id);
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

        @NotNull
        EventBuilder append(char c) {
            body.write(0x09);
            body.write(c);
            return this;
        }

        @NotNull
        EventBuilder append(@Nullable String str) {
            if (str == null) str = "";

            try {
                body.write(0x09);
                body.write(str.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }

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

    public static class PlaybackIntervals {

        @NotNull
        String toSend() {
            return "[[0,40000]]"; // FIXME
        }
    }
}
