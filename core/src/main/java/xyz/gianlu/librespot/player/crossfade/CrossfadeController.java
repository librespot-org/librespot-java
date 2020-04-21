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
    private static final Logger LOGGER = Logger.getLogger(CrossfadeController.class);
    private final int trackDuration;
    private final String fadeOutUri;
    private final FadeInterval fadeInInterval;
    private final FadeInterval fadeOutInterval;
    private final int defaultFadeDuration;
    private FadeInterval activeInterval = null;
    private float lastGain = 1;

    public CrossfadeController(int duration, @NotNull Map<String, String> metadata, @NotNull Player.Configuration conf) {
        trackDuration = duration;
        defaultFadeDuration = conf.crossfadeDuration();

        int fadeInDuration = Integer.parseInt(metadata.getOrDefault("audio.fade_in_duration", "-1"));
        int fadeInStartTime = Integer.parseInt(metadata.getOrDefault("audio.fade_in_start_time", "-1"));
        JsonArray fadeInCurves = JsonParser.parseString(metadata.getOrDefault("audio.fade_in_curves", "[]")).getAsJsonArray();
        if (fadeInCurves.size() > 1) throw new UnsupportedOperationException(fadeInCurves.toString());

        fadeOutUri = metadata.get("audio.fade_out_uri");
        int fadeOutDuration = Integer.parseInt(metadata.getOrDefault("audio.fade_out_duration", "-1"));
        int fadeOutStartTime = Integer.parseInt(metadata.getOrDefault("audio.fade_out_start_time", "-1"));
        JsonArray fadeOutCurves = JsonParser.parseString(metadata.getOrDefault("audio.fade_out_curves", "[]")).getAsJsonArray();
        if (fadeOutCurves.size() > 1) throw new UnsupportedOperationException(fadeOutCurves.toString());

        if (fadeInDuration == 0)
            fadeInInterval = null;
        else if (fadeInCurves.size() > 0)
            fadeInInterval = new FadeInterval(fadeInStartTime, fadeInDuration, LookupInterpolator.fromJson(getFadeCurve(fadeInCurves)));
        else if (defaultFadeDuration > 0)
            fadeInInterval = new FadeInterval(0, defaultFadeDuration, new LinearIncreasingInterpolator());
        else
            fadeInInterval = null;

        if (fadeOutDuration == 0)
            fadeOutInterval = null;
        else if (fadeOutCurves.size() > 0)
            fadeOutInterval = new FadeInterval(fadeOutStartTime, fadeOutDuration, LookupInterpolator.fromJson(getFadeCurve(fadeOutCurves)));
        else if (defaultFadeDuration > 0)
            fadeOutInterval = new FadeInterval(trackDuration - defaultFadeDuration, defaultFadeDuration, new LinearDecreasingInterpolator());
        else
            fadeOutInterval = null;

        LOGGER.debug(String.format("Loaded crossfade intervals. {start: %s, end: %s}", fadeInInterval, fadeOutInterval));
    }

    @NotNull
    private static JsonArray getFadeCurve(@NotNull JsonArray curves) {
        JsonObject curve = curves.get(0).getAsJsonObject();
        if (curve.get("start_point").getAsFloat() != 0 || curve.get("end_point").getAsFloat() != 1)
            throw new UnsupportedOperationException();

        return curve.getAsJsonArray("fade_curve");
    }

    public float getGain(int pos) {
        if (activeInterval != null && activeInterval.end() <= pos) {
            lastGain = activeInterval.interpolator.last();
            activeInterval = null;
        }

        if (activeInterval == null) {
            if (fadeInInterval != null && pos >= fadeInInterval.start && fadeInInterval.end() >= pos)
                activeInterval = fadeInInterval;
            else if (fadeOutInterval != null && pos >= fadeOutInterval.start && fadeOutInterval.end() >= pos)
                activeInterval = fadeOutInterval;
        }

        if (activeInterval == null) return lastGain;

        return lastGain = activeInterval.interpolate(pos);
    }

    public boolean fadeInEnabled() {
        return fadeInInterval != null;
    }

    public int fadeInStartTime() {
        if (fadeInInterval != null) return fadeInInterval.start;
        else return 0;
    }

    public int fadeInEndTime() {
        if (fadeInInterval != null) return fadeInInterval.end();
        else return defaultFadeDuration;
    }

    public boolean fadeOutEnabled() {
        return fadeOutInterval != null;
    }

    public int fadeOutStartTime() {
        if (fadeOutInterval != null) return fadeOutInterval.start;
        else return trackDuration - defaultFadeDuration;
    }

    public int fadeOutEndTime() {
        if (fadeOutInterval != null) return fadeOutInterval.end();
        else return trackDuration;
    }

    public int fadeOutStartTimeFromEnd() {
        if (fadeOutInterval != null) return trackDuration - fadeOutInterval.start;
        else return defaultFadeDuration;
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
            float pos = ((float) trackPos - start) / duration;
            pos = Math.min(pos, 1);
            pos = Math.max(pos, 0);
            return interpolator.interpolate(pos);
        }

        @Override
        public String toString() {
            return "FadeInterval{start=" + start + ", duration=" + duration + ", interpolator=" + interpolator + '}';
        }
    }
}
