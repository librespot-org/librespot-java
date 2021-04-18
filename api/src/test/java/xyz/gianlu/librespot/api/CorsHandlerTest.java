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

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorsHandlerTest {
    private final TestHandler testHandler = new TestHandler();
    private final CorsHandler corsHandler = new CorsHandler(testHandler);
    private final HttpServerExchange dummyExchange = new HttpServerExchange(null, new HeaderMap(), new HeaderMap(), 1000);

    @Test
    void shouldDelegate() throws Exception {
        corsHandler.handleRequest(dummyExchange);
        assertTrue(testHandler.hasCalled());
    }

    @Test
    void shouldHaveCorsHeader() throws Exception {
        corsHandler.handleRequest(dummyExchange);

        String value = dummyExchange.getResponseHeaders().getFirst("Access-Control-Allow-Origin");
        assertEquals("*", value);
    }

    private static class TestHandler implements HttpHandler {
        private boolean handledRequest = false;

        @Override
        public void handleRequest(HttpServerExchange exchange) {
            handledRequest = true;
        }

        private boolean hasCalled() {
            return handledRequest;
        }
    }
}