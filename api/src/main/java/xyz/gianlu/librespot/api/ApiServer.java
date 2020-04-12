package xyz.gianlu.librespot.api;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.RoutingHandler;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.api.handlers.*;

public class ApiServer {
    private static final Logger LOGGER = Logger.getLogger(ApiServer.class);
    private final int port;
    private final String host;
    private final HttpHandler handler;
    private Undertow undertow = null;

    public ApiServer(@NotNull ApiConfiguration conf, @NotNull SessionWrapper wrapper) {
        this.port = conf.apiPort();
        this.host = conf.apiHost();

        EventsHandler events = new EventsHandler();
        wrapper.setListener(events);

        handler = new CorsHandler(new RoutingHandler()
                .post("/player/{cmd}", new PlayerHandler(wrapper))
                .post("/metadata/{type}/{uri}", new MetadataHandler(wrapper, true))
                .post("/metadata/{uri}", new MetadataHandler(wrapper, false))
                .post("/search/{query}", new SearchHandler(wrapper))
                .post("/token/{scope}", new TokensHandler(wrapper))
                .get("/events", events));
    }

    public void start() {
        if (undertow != null) throw new IllegalStateException("Already started!");

        undertow = Undertow.builder().addHttpListener(port, host, handler).build();
        undertow.start();
        LOGGER.info(String.format("Server started on port %d!", port));
    }

    public void stop() {
        if (undertow != null) {
            undertow.stop();
            undertow = null;
        }

        LOGGER.info("Server stopped!");
    }
}
