package xyz.gianlu.librespot.api;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RoutingHandler;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;

class CorsHandler implements HttpHandler {
    private final HttpHandler handler;

    public CorsHandler(HttpHandler handler) {
        this.handler = handler;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        HeaderMap responseHeaders = exchange.getResponseHeaders();
        responseHeaders.add(HttpString.tryFromString("Access-Control-Allow-Origin"), "*");
        handler.handleRequest(exchange);
    }
}
