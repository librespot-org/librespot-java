package org.librespot.spotify;

import com.google.protobuf.ByteString;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.proto.Authentication;
import org.librespot.spotify.utils.ApResolver;
import org.librespot.spotify.utils.OnResult;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) {
        ApResolver.get().list(new OnResult<List<String>>() {
            @Override
            public void onResult(@NotNull List<String> result) {
                try {
                    Session session = new Session(result.get(ThreadLocalRandom.current().nextInt(result.size())));
                    session.authenticate(args[0], Authentication.AuthenticationType.AUTHENTICATION_USER_PASS, ByteString.copyFromUtf8(args[1]));
                } catch (IOException | NoSuchAlgorithmException | InvalidKeyException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onException(@NotNull Exception ex) {
                ex.printStackTrace();
            }
        });
    }
}
