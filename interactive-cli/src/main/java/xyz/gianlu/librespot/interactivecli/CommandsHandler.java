package xyz.gianlu.librespot.interactivecli;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.RawMercuryRequest;

import java.io.IOException;

/**
 * @author Gianlu
 */
public class CommandsHandler {
    private final static Logger LOGGER = Logger.getLogger(CommandsHandler.class);
    private final Session session;

    public CommandsHandler(@NotNull Session session) {
        this.session = session;
    }

    public void handle(@NotNull String cmd) throws IOException {
        if (cmd.startsWith("play")) {
            session.player().play();
        } else if (cmd.startsWith("pause")) {
            session.player().pause();
        } else if (cmd.startsWith("next")) {
            session.player().next();
        } else if (cmd.startsWith("prev")) {
            session.player().previous();
        } else if (cmd.startsWith("currentlyPlaying")) {
            LOGGER.info("Currently playing) { " + session.player().currentPlayableId());
        } else if (cmd.startsWith("request")) {
            String[] split = cmd.split("\\s");
            if (split.length != 3) {
                LOGGER.warn("Invalid command!");
                return;
            }

            MercuryClient.Response resp = session.mercury().sendSync(RawMercuryRequest.newBuilder()
                    .setMethod(split[1]).setUri(split[2])
                    .build());

            LOGGER.info("Uri: " + resp.uri);
            LOGGER.info("Status code: " + resp.statusCode);
            LOGGER.info("Payload: " + resp.payload.toHex());
        } else {
            LOGGER.warn("Unknown command: " + cmd);
        }
    }
}
