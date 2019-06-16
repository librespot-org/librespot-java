package xyz.gianlu.librespot.dealer;

import com.google.protobuf.AbstractMessageLite;
import com.spotify.connectstate.model.Connect;
import okhttp3.*;
import okio.BufferedSink;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.core.ApResolver;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;

import java.io.IOException;

/**
 * @author Gianlu
 */
public class ApiClient {
    private final Session session;
    private final OkHttpClient client;
    private final String baseUrl;

    public ApiClient(@NotNull Session session) {
        this.session = session;
        this.client = new OkHttpClient();
        this.baseUrl = "https://" + ApResolver.getRandomSpclient();
    }

    @NotNull
    private static RequestBody protoBody(@NotNull AbstractMessageLite msg) {
        return new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.get("application/protobuf");
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                sink.write(msg.toByteArray());
            }
        };
    }

    public void putConnectState(@NotNull String connectionId, @NotNull Connect.PutStateRequest proto) throws IOException, MercuryClient.MercuryException {
        send("PUT", "/connect-state/v1/devices/" + session.deviceId(), new Headers.Builder()
                .add("X-Spotify-Connection-Id", connectionId).build(), protoBody(proto));
    }

    @NotNull
    public Response send(@NotNull String method, @NotNull String suffix, @Nullable Headers headers, @Nullable RequestBody body) throws IOException, MercuryClient.MercuryException {
        Request.Builder request = new Request.Builder();
        request.method(method, body);
        if (headers != null) request.headers(headers);
        request.addHeader("Authorization", "Bearer " + session.tokens().get("playlist-read", null));
        request.url(baseUrl + suffix);

        return client.newCall(request.build()).execute();
    }
}
