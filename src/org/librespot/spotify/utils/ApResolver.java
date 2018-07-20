package org.librespot.spotify.utils;

import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author Gianlu
 */
public class ApResolver {
    private static final URL APRESOLVE_URL;
    private static final URL APRESOLVE_URL_FALLBACK;
    private static ApResolver instance;

    static {
        try {
            APRESOLVE_URL = new URL("http://apresolve.spotify.com/");
            APRESOLVE_URL_FALLBACK = new URL("http://ap.spotify.com/");
        } catch (MalformedURLException ex) {
            throw new RuntimeException(ex);
        }
    }

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private List<String> cached = null;

    private ApResolver() {
    }

    @NotNull
    public static ApResolver get() {
        if (instance == null) instance = new ApResolver();
        return instance;
    }

    @NotNull
    private static List<String> request(URL url) throws IOException, JSONException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.connect();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            JSONObject obj = new JSONObject(reader.readLine());
            List<String> list = new ArrayList<>();
            JSONArray array = obj.getJSONArray("ap_list");
            for (int i = 0; i < array.length(); i++) list.add(array.getString(i));
            return list;
        }
    }

    public void list(@NotNull OnResult<List<String>> listener) {
        if (cached != null) {
            listener.onResult(cached);
        } else {
            executorService.execute(() -> {
                try {
                    listener.onResult(request(APRESOLVE_URL));
                } catch (IOException | JSONException ex) {
                    try {
                        listener.onResult(request(APRESOLVE_URL_FALLBACK));
                    } catch (IOException | JSONException exx) {
                        listener.onException(ex);
                    }
                }
            });
        }
    }
}
