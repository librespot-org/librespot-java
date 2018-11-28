package xyz.gianlu.librespot.api;

import xyz.gianlu.librespot.api.server.ApiServer;

import java.io.IOException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException {
        ApiServer server = new ApiServer(24879);
        server.registerHandler(new PlayerHandler());
        server.registerHandler(new MetadataHandler());
    }
}
