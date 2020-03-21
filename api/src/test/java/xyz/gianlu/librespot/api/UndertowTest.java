package xyz.gianlu.librespot.api;

import io.undertow.Undertow;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class UndertowTest {
    /**
     * From https://gist.github.com/vorburger/3429822
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

    @Test
    public void shouldRespondWithCorsHeaders() throws Exception {
        int port = findFreePort();
        Undertow undertow = Undertow.builder()
                .addHttpListener(port, "", new CorsHandler(exchange -> {
                }))
                .build();

        try {
            undertow.start();
            try (Response response = new OkHttpClient().newCall(new Request.Builder()
                    .url("http://localhost:" + port + "/test").get().build()).execute()) {
                assertTrue(response.isSuccessful());
                assertEquals("*", response.header("Access-Control-Allow-Origin"));
            }
        } finally {
            undertow.stop();
        }
    }
}