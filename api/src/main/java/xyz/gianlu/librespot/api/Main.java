package xyz.gianlu.librespot.api;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.api.server.Receiver;
import xyz.gianlu.librespot.api.server.Sender;
import xyz.gianlu.librespot.api.server.WebsocketServer;

import java.io.IOException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException {
        WebsocketServer server = new WebsocketServer(24879, new Receiver() {
            @Override
            public void onReceivedText(@NotNull Sender sender, @NotNull String payload) {
                sender.sendText(payload);
            }

            @Override
            public void onReceivedBytes(@NotNull Sender sender, @NotNull byte[] payload) {
                sender.sendBytes(payload);
            }
        });
    }
}
