package xyz.gianlu.librespot.api.server;

import org.apache.log4j.Logger;
import xyz.gianlu.librespot.api.MercuryHandler;
import xyz.gianlu.librespot.api.MetadataHandler;
import xyz.gianlu.librespot.api.PlayerHandler;
import xyz.gianlu.librespot.core.Session;

import java.io.IOException;
import java.util.Observable;
import java.util.Observer;

public class ZeroconfApiServer extends ApiServer implements Observer {

    private static final Logger LOGGER = Logger.getLogger(ZeroconfApiServer.class);

    public ZeroconfApiServer(int port) throws IOException {
        super(port);
    }

    @Override
    public void update(Observable o, Object arg) {
        if (arg instanceof Session) {
            Session session = (Session) arg;
            LOGGER.info("Got session update, clearing old handlers");
            clearHandlers();

            registerHandler(new PlayerHandler(session));
            registerHandler(new MetadataHandler(session));
            registerHandler(new MercuryHandler(session));
            LOGGER.info("Registered new handlers for session");
        }
    }

}
