package xyz.gianlu.librespot.player.crossfade;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.player.Player;

import java.util.Map;

public class CrossfadeController {
    private final static JsonParser PARSER = new JsonParser();
    private static final Logger LOGGER = Logger.getLogger(CrossfadeController.class);
    private final int trackDuration;
    private final int defaultFadeDuration;
    private final int fadeInDuration;
    private final int fadeInStartTime;
    private final int fadeOutDuration;
    private final int fadeOutStartTime;
    private final String fadeOutUri;
    private final FadeInterval startInterval;
    private final FadeInterval endInterval;
    private FadeInterval activeInterval = null;
    private float lastGain = 1;

    public CrossfadeController(int duration, @NotNull Map<String, String> metadata, @NotNull Player.Configuration conf) {
        trackDuration = duration;
        defaultFadeDuration = conf.crossfadeDuration();

        fadeInDuration = Integer.parseInt(metadata.getOrDefault("audio.fade_in_duration", "-1"));
        fadeInStartTime = Integer.parseInt(metadata.getOrDefault("audio.fade_in_start_time", "-1"));
        JsonArray fadeInCurves = PARSER.parse(metadata.getOrDefault("audio.fade_in_curves", "[]")).getAsJsonArray();
        if (fadeInCurves.size() > 1) throw new UnsupportedOperationException(fadeInCurves.toString());

        fadeOutUri = metadata.get("audio.fade_out_uri");
        fadeOutDuration = Integer.parseInt(metadata.getOrDefault("audio.fade_out_duration", "-1"));
        fadeOutStartTime = Integer.parseInt(metadata.getOrDefault("audio.fade_out_start_time", "-1"));
        JsonArray fadeOutCurves = PARSER.parse(metadata.getOrDefault("audio.fade_out_curves", "[]")).getAsJsonArray();
        if (fadeOutCurves.size() > 1) throw new UnsupportedOperationException(fadeOutCurves.toString());


        if (fadeInCurves.size() > 0)
            startInterval = new FadeInterval(fadeInStartTime, fadeInDuration, LookupInterpolator.fromJson(getFadeCurve(fadeInCurves)));
        else if (defaultFadeDuration > 0)
            startInterval = new FadeInterval(0, defaultFadeDuration, new LinearIncreasingInterpolator());
        else
            startInterval = null;

        if (fadeOutCurves.size() > 0)
            endInterval = new FadeInterval(fadeOutStartTime, fadeOutDuration, LookupInterpolator.fromJson(getFadeCurve(fadeOutCurves)));
        else if (defaultFadeDuration > 0)
            endInterval = new FadeInterval(trackDuration - defaultFadeDuration, defaultFadeDuration, new LinearDecreasingInterpolator());
        else
            endInterval = null;

        LOGGER.debug(String.format("Loaded intervals. {start: %s, end: %s}", startInterval, endInterval));
    }

    @NotNull
    private static JsonArray getFadeCurve(@NotNull JsonArray curves) {
        JsonObject curve = curves.get(0).getAsJsonObject();
        if (curve.get("start_point").getAsFloat() != 0 || curve.get("end_point").getAsFloat() != 1)
            throw new UnsupportedOperationException();

        return curve.getAsJsonArray("fade_curve");
    }

    public boolean shouldStartNextTrack(int pos) {
        return fadeOutEnabled() && pos >= fadeOutStartTime;
    }

    public boolean shouldStop(int pos) {
        return endInterval != null && pos >= endInterval.end();
    }

    public float getGain(int pos) {
        if (activeInterval != null && activeInterval.end() <= pos) activeInterval = null;

        if (activeInterval == null) {
            if (startInterval != null && pos >= startInterval.start && startInterval.end() >= pos)
                activeInterval = startInterval;
            else if (endInterval != null && pos >= endInterval.start && endInterval.end() >= pos)
                activeInterval = endInterval;
        }

        if (activeInterval == null) return lastGain;

        return lastGain = activeInterval.interpolate(pos);
    }

    public int fadeInStartTime() {
        if (fadeInStartTime != -1) return fadeInStartTime;
        else return 0;
    }

    public int fadeOutStartTime() {
        if (fadeOutStartTime != -1) return fadeOutStartTime;
        else return trackDuration - defaultFadeDuration;
    }

    public int fadeOutDuration() {
        if (fadeOutDuration != -1) return fadeOutDuration;
        else return defaultFadeDuration;
    }

    public boolean fadeInEnabled() {
        return fadeInDuration != -1 || defaultFadeDuration > 0;
    }

    public boolean fadeOutEnabled() {
        return fadeOutDuration != -1 || defaultFadeDuration > 0;
    }

    @Nullable
    public String fadeOutUri() {
        return fadeOutUri;
    }

    private static class FadeInterval {
        final int start;
        final int duration;
        final GainInterpolator interpolator;

        FadeInterval(int start, int duration, @NotNull GainInterpolator interpolator) {
            this.start = start;
            this.duration = duration;
            this.interpolator = interpolator;
        }

        int end() {
            return start + duration;
        }

        float interpolate(int trackPos) {
            int fadePos = trackPos - start;
            return interpolator.interpolate(((float) fadePos) / duration);
        }

        @Override
        public String toString() {
            return "FadeInterval{start=" + start + ", duration=" + duration + ", interpolator=" + interpolator + '}';
        }
    }
}
