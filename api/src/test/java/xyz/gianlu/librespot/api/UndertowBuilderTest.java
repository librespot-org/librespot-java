package xyz.gianlu.librespot.api;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class UndertowBuilderTest {
    @Test
    public void shouldRespondWithCorsHeaders() throws Exception {
        int port = findFreePort();
        HttpHandler httpHandler = exchange -> {
        };
        HttpHandler corsWrappedHandler = new CorsHandler(httpHandler);

        Undertow undertow = new UndertowBuilder(port, "", corsWrappedHandler).build();
        try {
            undertow.start();

            Request request = new Request.Builder().url("http://localhost:" + port + "/test")
                                                   .get()
                                                   .build();
            OkHttpClient client = new OkHttpClient();
            Response response = client.newCall(request)
                                      .execute();

            assertTrue(response.isSuccessful());
            assertEquals(response.header("Access-Control-Allow-Origin"), "*");

        } finally {
            undertow.stop();
        }
    }

    /**
     * from https://gist.github.com/vorburger/3429822
     *
     * <p>
     * Returns a free port number on localhost.
     * <p>
     * Heavily inspired from org.eclipse.jdt.launching.SocketUtil (to avoid a dependency to JDT just because of this).
     * Slightly improved with close() missing in JDT. And throws exception instead of returning -1.
     *
     * @return a free port number on localhost
     * @throws IllegalStateException if unable to find a free port
     */
    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            int port = socket.getLocalPort();
            try {
                socket.close();
            } catch (IOException e) {
                // Ignore IOException on close()
            }
            return port;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}