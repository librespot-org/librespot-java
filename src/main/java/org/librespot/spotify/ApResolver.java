package org.librespot.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @author Gianlu
 */
public class ApResolver {
    private static final JsonParser PARSER = new JsonParser();

    @NotNull
    public static List<String> getAccessPoints() throws IOException {
        try {
            return getAccessPoints("http://apresolve.spotify.com/");
        } catch (Exception ex) {
            return getAccessPoints("http://ap.spotify.com:443/");
        }
    }

    @NotNull
    private static List<String> getAccessPoints(@NotNull String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.connect();

        JsonObject obj = PARSER.parse(new InputStreamReader(conn.getInputStream())).getAsJsonObject();
        JsonArray aps = obj.getAsJsonArray("ap_list");

        List<String> list = new ArrayList<>(aps.size());
        for (JsonElement ap : aps) list.add(ap.getAsString());
        return list;
    }

    @NotNull
    public static String getRandomAccessPoint() throws IOException {
        List<String> aps = getAccessPoints();
        return aps.get(ThreadLocalRandom.current().nextInt(aps.size()));
    }

    @NotNull
    public static Socket getSocketFromRandomAccessPoint() throws IOException {
        String ap = getRandomAccessPoint();
        int colon = ap.indexOf(':');
        return new Socket(ap.substring(0, colon), Integer.parseInt(ap.substring(colon + 1)));
    }
}
