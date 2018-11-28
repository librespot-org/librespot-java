package xyz.gianlu.librespot.api;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException {
        WebsocketServer server = new WebsocketServer(24879, new WebsocketServer.Receiver() {
            @Override
            public void onReceivedText(WebsocketServer.@NotNull Sender sender, @NotNull String payload) {
                try {
                    sender.sendText(payload);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onReceivedBytes(WebsocketServer.@NotNull Sender sender, @NotNull byte[] payload) {
                try {
                    sender.sendBytes(payload);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }
}
