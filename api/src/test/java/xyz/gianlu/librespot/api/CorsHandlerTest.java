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
    private HttpServerExchange dummyExchange = new HttpServerExchange(null, new HeaderMap(), new HeaderMap(), 1000);

    @Test
    void shouldDelegate() throws Exception {
        corsHandler.handleRequest(dummyExchange);

        assertTrue(testHandler.hasCalled());
    }

    @Test
    void shouldHaveCorsHeader() throws Exception {
        corsHandler.handleRequest(dummyExchange);

        String firstValue = dummyExchange.getResponseHeaders()
                                       .get("Access-Control-Allow-Origin")
                                       .get(0);
        assertEquals(firstValue, "*");
    }

    private static class TestHandler implements HttpHandler {
        private boolean handledRequest;

        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            handledRequest = true;
        }

        private boolean hasCalled() {
            return handledRequest;
        }
    }
}