package xyz.gianlu.librespot.api;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;

/**
 * makes undertow with httpHandler configuration
 * testable without spinning up complete spotify config
 */
class UndertowBuilder {
    private final int port;
    private final String host;
    private final HttpHandler httpHandler;

    UndertowBuilder(int port, String host, HttpHandler httpHandler) {
        this.port = port;
        this.host = host;
        this.httpHandler = httpHandler;
    }

    Undertow build() {
        return Undertow.builder()
                       .addHttpListener(port, host, httpHandler)
                       .build();
    }
}
