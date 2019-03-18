package xyz.gianlu.librespot.player.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Gianlu
 */
public class Remote3Page {
    public final List<Remote3Track> tracks;
    public final String nextPageUrl;

    private Remote3Page(@NotNull JsonObject obj) {
        nextPageUrl = Utils.optString(obj, "next_page_url", null);

        JsonArray array = obj.getAsJsonArray("tracks");
        tracks = new ArrayList<>(array.size());
        for (JsonElement elm : array)
            tracks.add(new Remote3Track(elm.getAsJsonObject()));
    }

    @Nullable
    @Contract("null -> null")
    public static List<Remote3Page> opt(@Nullable JsonArray array) {
        if (array == null) return null;

        List<Remote3Page> pages = new ArrayList<>(array.size());
        for (JsonElement elm : array)
            pages.add(new Remote3Page(elm.getAsJsonObject()));

        return pages;
    }
}
