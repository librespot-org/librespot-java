package xyz.gianlu.librespot.interactivecli;

import com.google.gson.JsonObject;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.RawMercuryRequest;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Gianlu
 */
public class CommandsHandler {
    private final static Logger LOGGER = Logger.getLogger(CommandsHandler.class);
    private final Session session;
    private final Map<String, CustomCommand> customCommands = new HashMap<>();

    public CommandsHandler(@NotNull Session session) {
        this.session = session;
    }

    void handle(@NotNull String cmd) throws IOException {
        if (cmd.startsWith("play")) {
            session.player().play();
        } else if (cmd.startsWith("pause")) {
            session.player().pause();
        } else if (cmd.startsWith("next")) {
            session.player().next();
        } else if (cmd.startsWith("prev")) {
            session.player().previous();
        } else if (cmd.startsWith("currentlyPlaying")) {
            LOGGER.info("Currently playing: " + session.player().currentPlayableId());
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
            for (String custom : customCommands.keySet()) {
                if (cmd.startsWith(custom)) {
                    customCommands.get(custom).handle(cmd);
                    return;
                }
            }

            LOGGER.warn("Unknown command: " + cmd);
        }
    }

    void addCustomCommand(@NotNull JsonObject obj) {
        String startsWith = obj.get("startsWith").getAsString();
        customCommands.put(startsWith, new CustomCommand(obj));
    }

    private class CustomCommand {
        final int arguments;
        final String command;
        final String startsWith;

        CustomCommand(@NotNull JsonObject obj) {
            this.arguments = obj.get("arguments").getAsInt();
            this.command = obj.get("command").getAsString();
            this.startsWith = obj.get("startsWith").getAsString();

            if (command.startsWith(startsWith))
                throw new IllegalArgumentException("Check your command! You'll recurse infinitely.");
        }

        void handle(@NotNull String cmd) throws IOException {
            String[] split = cmd.split("\\s");
            if (split.length - 1 != arguments) {
                LOGGER.warn(String.format("Invalid command! Required %d argument(s), but given %d.", arguments, split.length - 1));
                return;
            }

            String[] args = Arrays.copyOfRange(split, 1, split.length);
            CommandsHandler.this.handle(String.format(command, (Object[]) args));
        }
    }
}
