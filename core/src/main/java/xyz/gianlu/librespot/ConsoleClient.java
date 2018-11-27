package xyz.gianlu.librespot;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.RawMercuryRequest;
import xyz.gianlu.librespot.common.Utils;

import java.io.IOException;
import java.util.Scanner;

/**
 * @author Gianlu
 */
public class ConsoleClient {
    private final MercuryClient client;
    private final Scanner scanner;

    private ConsoleClient(@NotNull Session session) {
        this.client = session.mercury();
        this.scanner = new Scanner(System.in);

        while (true) {
            try {
                loop();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    public static void start(@NotNull Session session) {
        new ConsoleClient(session);
    }

    private void loop() throws IOException {
        System.out.println("New Mercury request");
        System.out.print("Method (GET, SEND, SUB, UNSUB): ");

        String in = scanner.nextLine();
        String method = in;

        System.out.print("URI: ");
        in = scanner.nextLine();
        String uri = in;

        MercuryClient.Response resp = client.sendSync(RawMercuryRequest.newBuilder()
                .setUri(uri)
                .setMethod(method)
                .addUserField("Accept-Language", "en")
                .addUserField("Accept-Encoding", "gzip")
                .build());

        System.out.println("Status code: " + resp.statusCode);
        System.out.println("Response URI: " + resp.uri);
        System.out.println("Payloads: " + resp.payload);

        for (int i = 0; i < resp.payload.size(); i++) {
            System.out.println("Payload " + i + " HEX: " + Utils.bytesToHex(resp.payload.get(i)));
            System.out.println("Payload " + i + " string: " + new String(resp.payload.get(i)));
        }

        System.out.println();
    }
}
