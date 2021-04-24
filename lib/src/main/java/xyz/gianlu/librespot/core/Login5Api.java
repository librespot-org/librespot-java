/*
 * Copyright 2021 devgianlu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.gianlu.librespot.core;

import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.spotify.login5v3.ClientInfoOuterClass;
import com.spotify.login5v3.Hashcash;
import com.spotify.login5v3.Login5;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.mercury.MercuryRequests;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static xyz.gianlu.librespot.dealer.ApiClient.protoBody;

/**
 * @author devgianlu
 */
public final class Login5Api {
    private final Session session;

    public Login5Api(@NotNull Session session) {
        this.session = session;
    }

    private static boolean checkTenTrailingBits(byte[] array) {
        if (array[array.length - 1] != 0) return false;
        else return Integer.numberOfTrailingZeros(array[array.length - 2]) >= 2;
    }

    private static void incrementCtr(byte[] ctr, int index) {
        ctr[index]++;
        if (ctr[index] == 0 && index != 0)
            incrementCtr(ctr, index - 1);
    }

    @NotNull
    private static ChallengeSolve solveHashCash(byte[] prefix, int length, byte[] random) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA1");

        byte[] suffix = new byte[16];
        System.arraycopy(random, 0, suffix, 0, 8);

        assert length == 10;

        int iters = 0;
        while (true) {
            md.reset();
            md.update(prefix);
            md.update(suffix);
            byte[] digest = md.digest();
            if (checkTenTrailingBits(digest))
                return new ChallengeSolve(suffix, iters);

            incrementCtr(suffix, suffix.length - 1);
            incrementCtr(suffix, 7);
            iters++;
        }
    }

    @NotNull
    private static Login5.LoginRequest.Builder solveChallenge(@NotNull Login5.LoginResponse resp) throws NoSuchAlgorithmException {
        byte[] loginContext = resp.getLoginContext().toByteArray();

        Hashcash.HashcashChallenge hashcash = resp.getChallenges().getChallenges(0).getHashcash();

        byte[] prefix = hashcash.getPrefix().toByteArray();
        byte[] seed = new byte[8];
        byte[] loginContextDigest = MessageDigest.getInstance("SHA1").digest(loginContext);
        System.arraycopy(loginContextDigest, 12, seed, 0, 8);

        long start = System.nanoTime();
        ChallengeSolve solved = solveHashCash(prefix, hashcash.getLength(), seed);
        long durationNano = System.nanoTime() - start;

        return Login5.LoginRequest.newBuilder()
                .setLoginContext(ByteString.copyFrom(loginContext))
                .setChallengeSolutions(Login5.ChallengeSolutions.newBuilder()
                        .addSolutions(Login5.ChallengeSolution.newBuilder()
                                .setHashcash(Hashcash.HashcashSolution.newBuilder()
                                        .setDuration(Duration.newBuilder()
                                                .setSeconds((int) (durationNano / 1_000_000_000))
                                                .setNanos((int) (durationNano % 1_000_000_000))
                                                .build())
                                        .setSuffix(ByteString.copyFrom(solved.suffix))
                                        .build()).build()).build());
    }

    @NotNull
    private Login5.LoginResponse send(@NotNull Login5.LoginRequest msg) throws IOException {
        Request.Builder req = new Request.Builder()
                .method("POST", protoBody(msg))
                .url("https://login5.spotify.com/v3/login");

        try (Response resp = session.client().newCall(req.build()).execute()) {
            ResponseBody body = resp.body();
            if (body == null) throw new IOException("No body");
            return Login5.LoginResponse.parseFrom(body.bytes());
        }
    }

    @NotNull
    public Login5.LoginResponse login5(@NotNull Login5.LoginRequest req) throws IOException, NoSuchAlgorithmException {
        req = req.toBuilder()
                .setClientInfo(ClientInfoOuterClass.ClientInfo.newBuilder()
                        .setClientId(MercuryRequests.KEYMASTER_CLIENT_ID)
                        .setDeviceId(session.deviceId())
                        .build())
                .build();

        Login5.LoginResponse resp = send(req);
        if (resp.hasChallenges()) {
            Login5.LoginRequest.Builder reqq = solveChallenge(resp);
            reqq.setClientInfo(req.getClientInfo())
                    .setAppleSignInCredential(req.getAppleSignInCredential())
                    .setFacebookAccessToken(req.getFacebookAccessToken())
                    .setOneTimeToken(req.getOneTimeToken())
                    .setPhoneNumber(req.getPhoneNumber())
                    .setStoredCredential(req.getStoredCredential())
                    .setPassword(req.getPassword());
            resp = send(reqq.build());
        }

        return resp;
    }

    private static class ChallengeSolve {
        final byte[] suffix;
        final int ctr;

        ChallengeSolve(byte[] suffix, int ctr) {
            this.suffix = suffix;
            this.ctr = ctr;
        }
    }
}
