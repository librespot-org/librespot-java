/*
 * Copyright 2021 devgianlu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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