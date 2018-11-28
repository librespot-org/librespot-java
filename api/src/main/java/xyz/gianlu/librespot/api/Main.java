package xyz.gianlu.librespot.api;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException {
        ApiServer server = new ApiServer(24879, new ApiServer.Receiver() {
            @Override
            public void onReceivedText(ApiServer.@NotNull Sender sender, @NotNull String payload) {
                try {
                    sender.sendText(payload);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onReceivedBytes(ApiServer.@NotNull Sender sender, @NotNull byte[] payload) {
                try {
                    sender.sendBytes(payload);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }
}
