package xyz.gianlu.librespot.api;

import com.stijndewitt.undertow.cors.AllowAll;
import com.stijndewitt.undertow.cors.Filter;
import io.undertow.Undertow;
import io.undertow.server.RoutingHandler;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.api.handlers.EventsHandler;
import xyz.gianlu.librespot.api.handlers.MetadataHandler;
import xyz.gianlu.librespot.api.handlers.PlayerHandler;

public class ApiServer {
    private static final Logger LOGGER = Logger.getLogger(ApiServer.class);
    private final int port;
    private final String host;
    private final RoutingHandler handler;
    private Undertow undertow = null;

    public ApiServer(@NotNull ApiConfiguration conf, @NotNull SessionWrapper wrapper) {
        this.port = conf.apiPort();
        this.host = conf.apiHost();

        EventsHandler events = new EventsHandler();
        wrapper.setListener(events);

        handler = new RoutingHandler();
        handler.post("/player/{cmd}", new PlayerHandler(wrapper))
                .post("/metadata/{type}/{uri}", new MetadataHandler(wrapper))
                .get("/events", events);
    }

    public void start() {
        if (undertow != null) throw new IllegalStateException("Already started!");

        Filter corsFilter = new Filter(handler);
        corsFilter.setPolicyClass(AllowAll.class.getCanonicalName());
        corsFilter.setPolicyParam(null);
        corsFilter.setUrlPattern(".*");

        undertow = Undertow.builder().addHttpListener(port, host, corsFilter).build();
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
