package xyz.gianlu.librespot.api.server;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.api.MercuryHandler;
import xyz.gianlu.librespot.api.MetadataHandler;
import xyz.gianlu.librespot.api.PlayerHandler;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.ZeroconfServer;

import java.io.IOException;

public class ZeroconfApiServer extends ApiServer implements ZeroconfServer.SessionListener {
    private static final Logger LOGGER = Logger.getLogger(ZeroconfApiServer.class);

    public ZeroconfApiServer(int port) throws IOException {
        super(port);
    }

    @Override
    public void sessionChanged(@NotNull Session session) {
        clearHandlers();

        registerHandler(new PlayerHandler(session));
        registerHandler(new MetadataHandler(session));
        registerHandler(new MercuryHandler(session));
        LOGGER.info("Refreshed handlers for new session.");
    }
}
