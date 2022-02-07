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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @author Gianlu
 */
public final class ApResolver {
    private static final String BASE_URL = "http://apresolve.spotify.com/";
    private static final Logger LOGGER = LoggerFactory.getLogger(ApResolver.class);

    private final OkHttpClient client;
    private final Map<String, List<String>> pool = new HashMap<>(3);
    private volatile boolean poolReady = false;

    public ApResolver(OkHttpClient client) throws IOException {
        this.client = client;
        fillPool();
    }

    private void fillPool() throws IOException {
        request("accesspoint", "dealer", "spclient");
    }

    public void refreshPool() throws IOException {
        poolReady = false;
        pool.clear();
        fillPool();
    }

    @NotNull
    private static List<String> getUrls(@NotNull JsonObject body, @NotNull String type) {
        JsonArray aps = body.getAsJsonArray(type);
        List<String> list = new ArrayList<>(aps.size());
        for (JsonElement ap : aps) list.add(ap.getAsString());
        return list;
    }

    private void request(@NotNull String... types) throws IOException {
        if (types.length == 0) throw new IllegalArgumentException();

        StringBuilder url = new StringBuilder(BASE_URL + "?");
        for (int i = 0; i < types.length; i++) {
            if (i != 0) url.append("&");
            url.append("type=").append(types[i]);
        }

        Request request = new Request.Builder()
                .url(url.toString())
                .build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (body == null) throw new IOException("No body");
            JsonObject obj = JsonParser.parseReader(body.charStream()).getAsJsonObject();
            HashMap<String, List<String>> map = new HashMap<>();
            for (String type : types)
                map.put(type, getUrls(obj, type));

            synchronized (pool) {
                pool.putAll(map);
                poolReady = true;
                pool.notifyAll();
            }

            LOGGER.info("Loaded aps into pool: " + pool);
        }
    }

    private void waitForPool() {
        if (!poolReady) {
            synchronized (pool) {
                try {
                    pool.wait();
                } catch (InterruptedException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        }
    }

    @NotNull
    private String getRandomOf(@NotNull String type) {
        waitForPool();

        List<String> urls = pool.get(type);
        if (urls == null || urls.isEmpty()) throw new IllegalStateException();
        return urls.get(ThreadLocalRandom.current().nextInt(urls.size()));
    }

    @NotNull
    public String getRandomDealer() {
        return getRandomOf("dealer");
    }

    @NotNull
    public String getRandomSpclient() {
        return getRandomOf("spclient");
    }

    @NotNull
    public String getRandomAccesspoint() {
        return getRandomOf("accesspoint");
    }
}
