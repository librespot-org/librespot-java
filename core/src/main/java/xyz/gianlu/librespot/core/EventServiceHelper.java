package xyz.gianlu.librespot.core;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.RawMercuryRequest;
import xyz.gianlu.librespot.player.StateWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * @author Gianlu
 */
public final class EventServiceHelper {

    private EventServiceHelper() {
    }

    private static void sendEvent(@NotNull Session session, @NotNull EventBuilder builder) throws IOException {
        byte[] body = builder.toArray();
        MercuryClient.Response resp = session.mercury().sendSync(RawMercuryRequest.newBuilder()
                .setUri("hm://event-service/v1/events").setMethod("POST")
                .addUserField("Accept-Language", "en")
                .addUserField("X-ClientTimeStamp", String.valueOf(TimeProvider.currentTimeMillis()))
                .addPayloadPart(body)
                .build());

        System.out.println(EventBuilder.toString(body) + " => " + resp.statusCode);
    }

    public static void reportLang(@NotNull Session session, @NotNull String lang) throws IOException {
        EventBuilder event = new EventBuilder();
        event.chars("812");
        event.delimiter().chars('1');
        event.delimiter().chars(lang);
        event.delimiter();

        sendEvent(session, event);
    }

    public static void reportPlayback(@NotNull Session session, @NotNull StateWrapper state, @NotNull String uri, @NotNull PlaybackIntervals intervals) throws IOException {
        EventBuilder event = new EventBuilder();
        event.chars("372");
        event.delimiter().chars('1');
        event.delimiter().chars(state.getPlaybackId());
        event.delimiter().chars(uri);
        event.delimiter().chars('0');
        event.delimiter().chars(intervals.toSend());

        sendEvent(session, event);
    }

    public static void announceNewPlaybackId(@NotNull Session session, @NotNull StateWrapper state) throws IOException {
        EventBuilder event = new EventBuilder();
        event.chars("558");
        event.delimiter().chars('1');
        event.delimiter().chars(state.getPlaybackId());
        event.delimiter().chars(state.getSessionId());
        event.delimiter().chars(String.valueOf(TimeProvider.currentTimeMillis()));

        sendEvent(session, event);
    }

    public static void announceNewSessionId(@NotNull Session session, @NotNull StateWrapper state) throws IOException {
        EventBuilder event = new EventBuilder();
        event.chars("557");
        event.delimiter().chars('3');
        event.delimiter().chars(state.getSessionId());

        String contextUri = state.getContextUri();
        event.delimiter().chars(contextUri);
        event.delimiter().chars(contextUri);
        event.delimiter().chars(String.valueOf(TimeProvider.currentTimeMillis()));
        event.delimiter().delimiter().chars(String.valueOf(state.getContextSize()));
        event.delimiter().chars("context://" + state.getContextUri()); // FIXME: Might not be this way

        sendEvent(session, event);
    }

    private static class EventBuilder {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream(256);

        EventBuilder() {
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

        EventBuilder chars(char c) {
            body.write(c);
            return this;
        }

        EventBuilder chars(@NotNull String str) throws IOException {
            body.write(str.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        EventBuilder delimiter() {
            body.write(0x09);
            return this;
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
