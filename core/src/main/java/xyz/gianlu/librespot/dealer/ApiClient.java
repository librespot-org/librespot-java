package xyz.gianlu.librespot.dealer;

import com.spotify.connectstate.model.Connect;
import okhttp3.*;
import okio.BufferedSink;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;

import java.io.IOException;

/**
 * @author Gianlu
 */
public class ApiClient {
    private final Session session;
    private final OkHttpClient client;

    public ApiClient(@NotNull Session session) {
        this.session = session;
        this.client = new OkHttpClient();
    }

    public void send(String connId, byte[] payload) throws IOException, MercuryClient.MercuryException {
        Response resp = client.newCall(new Request.Builder()
                .url("https://spclient.wg.spotify.com/connect-state/v1/devices/" + session.deviceId())
                .method("PUT", new RequestBody() {
                    @Override
                    public MediaType contentType() {
                        return MediaType.get("application/protobuf");
                    }

                    @Override
                    public void writeTo(BufferedSink sink) throws IOException {
                        sink.write(payload);
                    }
                })
                .header("X-Spotify-Connection-Id", connId)
                .header("Authorization", "Bearer " + session.tokens().get("playlist-read", null))
                .build()).execute();

        System.out.println(resp);

        if (resp.code() == 200) {
            ResponseBody body = resp.body();
            System.out.println(Connect.Cluster.parseFrom(body.byteStream()));
        }
    }
}
