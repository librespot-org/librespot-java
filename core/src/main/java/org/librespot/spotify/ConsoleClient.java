package org.librespot.spotify;

import com.google.protobuf.ByteString;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.core.Session;
import org.librespot.spotify.mercury.MercuryClient;
import org.librespot.spotify.proto.Mercury;

import java.io.IOException;
import java.util.Arrays;
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
        System.out.print("Method: ");

        String in = scanner.nextLine();
        MercuryClient.Method method = MercuryClient.Method.valueOf(in);

        System.out.print("URI: ");
        in = scanner.nextLine();
        String uri = in;

        Mercury.UserField[] fields = new Mercury.UserField[2];
        fields[0] = Mercury.UserField.newBuilder().setKey("Accept-Language").setValue(ByteString.copyFromUtf8("en")).build();
        fields[1] = Mercury.UserField.newBuilder().setKey("Accept-Encoding").setValue(ByteString.copyFromUtf8("gzip")).build();

        MercuryClient.Response resp = client.sendSync(uri, method, fields, new byte[0][]);

        System.out.println("Status code: " + resp.statusCode);
        System.out.println("Response URI: " + resp.uri);
        System.out.println("Payload: " + Arrays.deepToString(resp.payload));
        if (resp.payload.length > 0) System.out.println("Payload 0 string: " + new String(resp.payload[0]));

        System.out.println();
    }
}
