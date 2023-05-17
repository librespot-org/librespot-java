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

package xyz.gianlu.librespot.player.crossfade;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gianlu.librespot.metadata.PlayableId;
import xyz.gianlu.librespot.player.PlayerConfiguration;
import xyz.gianlu.librespot.player.metrics.PlaybackMetrics.Reason;

import java.util.HashMap;
import java.util.Map;

public class CrossfadeController {
    private static final Logger LOGGER = LoggerFactory.getLogger(CrossfadeController.class);
    private final String playbackId;
    private final int trackDuration;
    private final Map<Reason, FadeInterval> fadeOutMap = new HashMap<>(8);
    private final Map<Reason, FadeInterval> fadeInMap = new HashMap<>(8);
    private final int defaultFadeDuration;
    private final PlayableId fadeOutPlayable;
    private FadeInterval fadeIn = null;
    private FadeInterval fadeOut = null;
    private FadeInterval activeInterval = null;
    private float lastGain = 1;
    private int fadeOverlap = 0;

    public CrossfadeController(@NotNull String playbackId, int duration, @NotNull Map<String, String> metadata, @NotNull PlayerConfiguration conf) {
        this.playbackId = playbackId;
        trackDuration = duration;
        defaultFadeDuration = conf.crossfadeDuration;

        String fadeOutUri = metadata.get("audio.fade_out_uri");
        fadeOutPlayable = fadeOutUri == null ? null : PlayableId.fromUri(fadeOutUri);

        populateFadeIn(metadata);
        populateFadeOut(metadata);

        LOGGER.debug("Loaded crossfade intervals {id: {}, in: {}, out: {}}", playbackId, fadeInMap, fadeOutMap);
    }

    @NotNull
    private static JsonArray getFadeCurve(@NotNull JsonArray curves) {
        JsonObject curve = curves.get(0).getAsJsonObject();
        if (curve.get("start_point").getAsFloat() != 0 || curve.get("end_point").getAsFloat() != 1)
            throw new UnsupportedOperationException();

        return curve.getAsJsonArray("fade_curve");
    }

    private void populateFadeIn(@NotNull Map<String, String> metadata) {
        int fadeInDuration = Integer.parseInt(metadata.getOrDefault("audio.fade_in_duration", "-1"));
        int fadeInStartTime = Integer.parseInt(metadata.getOrDefault("audio.fade_in_start_time", "-1"));
        JsonArray fadeInCurves = JsonParser.parseString(metadata.getOrDefault("audio.fade_in_curves", "[]")).getAsJsonArray();
        if (fadeInCurves.size() > 1) throw new UnsupportedOperationException(fadeInCurves.toString());

        if (fadeInDuration != 0 && fadeInCurves.size() > 0)
            fadeInMap.put(Reason.TRACK_DONE, new FadeInterval(fadeInStartTime, fadeInDuration, LookupInterpolator.fromJson(getFadeCurve(fadeInCurves))));
        else if (defaultFadeDuration > 0)
            fadeInMap.put(Reason.TRACK_DONE, new FadeInterval(0, defaultFadeDuration, new LinearIncreasingInterpolator()));


        int fwdFadeInStartTime = Integer.parseInt(metadata.getOrDefault("audio.fwdbtn.fade_in_start_time", "-1"));
        int fwdFadeInDuration = Integer.parseInt(metadata.getOrDefault("audio.fwdbtn.fade_in_duration", "-1"));
        if (fwdFadeInDuration > 0)
            fadeInMap.put(Reason.FORWARD_BTN, new FadeInterval(fwdFadeInStartTime, fwdFadeInDuration, new LinearIncreasingInterpolator()));

        int backFadeInStartTime = Integer.parseInt(metadata.getOrDefault("audio.backbtn.fade_in_start_time", "-1"));
        int backFadeInDuration = Integer.parseInt(metadata.getOrDefault("audio.backbtn.fade_in_duration", "-1"));
        if (backFadeInDuration > 0)
            fadeInMap.put(Reason.BACK_BTN, new FadeInterval(backFadeInStartTime, backFadeInDuration, new LinearIncreasingInterpolator()));
    }


abstract class Fade {
    protected Reason reason;
    protected Map<String, String> metadata;

    public Fade(Reason reason, Map<String, String> metadata) {
        this.reason = reason;
        this.metadata = metadata;
    }

    abstract void populateFade();
}
    class TrackDoneFade extends Fade {
        public TrackDoneFade(Reason reason, Map<String, String> metadata) {
            super(reason, metadata);
        }

        @Override
        void populateFade() {
            int fadeDuration = Integer.parseInt(metadata.getOrDefault("audio.fade_out_duration", "-1"));
            int fadeStartTime = Integer.parseInt(metadata.getOrDefault("audio.fade_out_start_time", "-1"));
            JsonArray fadeCurves = JsonParser.parseString(metadata.getOrDefault("audio.fade_out_curves", "[]")).getAsJsonArray();
            if (fadeCurves.size() > 1) throw new UnsupportedOperationException(fadeCurves.toString());

            if (fadeDuration != 0 && fadeCurves.size() > 0)
                fadeOutMap.put(reason, new FadeInterval(fadeStartTime, fadeDuration, LookupInterpolator.fromJson(getFadeCurve(fadeCurves))));
            else if (defaultFadeDuration > 0)
                fadeOutMap.put(reason, new FadeInterval(trackDuration - defaultFadeDuration, defaultFadeDuration, new LinearDecreasingInterpolator()));
        }
    }

    class PartialFade extends Fade {
        private String fadeType;

        public PartialFade(Reason reason, Map<String, String> metadata, String fadeType) {
            super(reason, metadata);
            this.fadeType = fadeType;
        }

        @Override
        void populateFade() {
            int fadeOutDuration = Integer.parseInt(metadata.getOrDefault(String.format("audio.%s.fade_out_duration", fadeType), "-1"));
            if (fadeOutDuration > 0)
                fadeOutMap.put(reason, new PartialFadeInterval(fadeOutDuration, new LinearDecreasingInterpolator()));
        }
    }
    private void populateFadeOut(@NotNull Map<String, String> metadata) {
        Fade fade;

        if (metadata.containsKey("audio.fade_out_duration")) {
            fade = new TrackDoneFade(Reason.TRACK_DONE, metadata);
        } else {
            if (metadata.containsKey("audio.backbtn.fade_out_duration")) {
                fade = new PartialFade(Reason.BACK_BTN, metadata, "backbtn");
            } else if (metadata.containsKey("audio.fwdbtn.fade_out_duration")) {
                fade = new PartialFade(Reason.FORWARD_BTN, metadata, "fwdbtn");
            } else {
                return;
            }
        }

        fade.populateFade();
    }


    /**
     * Get the gain at this specified position, switching out intervals if needed.
     *
     * @param pos The time in milliseconds
     * @return The gain value from 0 to 1
     */
    public float getGain(int pos) {
        if (activeInterval == null && fadeIn == null && fadeOut == null)
            return lastGain;

        if (activeInterval != null && activeInterval.end() <= pos) {
            lastGain = activeInterval.interpolator.last();

            if (activeInterval == fadeIn) {
                fadeIn = null;
                LOGGER.debug("Cleared fade in. {id: {}}", playbackId);
            } else if (activeInterval == fadeOut) {
                fadeOut = null;
                LOGGER.debug("Cleared fade out. {id: {}}", playbackId);
            }

            activeInterval = null;
        }

        if (activeInterval == null) {
            if (fadeIn != null && pos >= fadeIn.start && fadeIn.end() >= pos) {
                activeInterval = fadeIn;
                fadeOverlap += fadeIn.duration;
            } else if (fadeOut != null && pos >= fadeOut.start && fadeOut.end() >= pos) {
                activeInterval = fadeOut;
                fadeOverlap += fadeOut.duration;
            }
        }

        if (activeInterval == null) return lastGain;

        return lastGain = activeInterval.interpolate(pos);
    }

    /**
     * Select the next fade in interval. This field will be cleared once the interval has started and then left.
     *
     * @param reason     The reason behind this change, used to get the correct interval
     * @param customFade Whether the previous track was {@link CrossfadeController#fadeOutPlayable()}
     * @return The interval that has just been selected
     */
    @Nullable
    public FadeInterval selectFadeIn(@NotNull Reason reason, boolean customFade) {
        if ((!customFade && fadeOutPlayable != null) && reason == Reason.TRACK_DONE) {
            fadeIn = null;
            activeInterval = null;
            LOGGER.debug("Cleared fade in because custom fade doesn't apply. {id: {}}", playbackId);
            return null;
        } else {
            fadeIn = fadeInMap.get(reason);
            activeInterval = null;
            LOGGER.debug("Changed fade in. {curr: {}, custom: {}, why: {}, id: {}}", fadeIn, customFade, reason, playbackId);
            return fadeIn;
        }
    }

    /**
     * Select the next fade out interval. This field will be cleared once the interval has started and then left.
     *
     * @param reason     The reason behind this change, used to get the correct interval
     * @param customFade Whether the next track is {@link CrossfadeController#fadeOutPlayable()}
     * @return The interval that has just been selected
     */
    @Nullable
    public FadeInterval selectFadeOut(@NotNull Reason reason, boolean customFade) {
        if ((!customFade && fadeOutPlayable != null) && reason == Reason.TRACK_DONE) {
            fadeOut = null;
            activeInterval = null;
            LOGGER.debug("Cleared fade out because custom fade doesn't apply. {id: {}}", playbackId);
            return null;
        } else {
            fadeOut = fadeOutMap.get(reason);
            activeInterval = null;
            LOGGER.debug("Changed fade out. {curr: {}, custom: {}, why: {}, id: {}}", fadeOut, customFade, reason, playbackId);
            return fadeOut;
        }
    }

    /**
     * @return The first (scheduled) fade out start time.
     */
    public int fadeOutStartTimeMin() {
        int fadeOutStartTime = -1;
        for (FadeInterval interval : fadeOutMap.values()) {
            if (interval instanceof PartialFadeInterval) continue;

            if (fadeOutStartTime == -1 || fadeOutStartTime > interval.start)
                fadeOutStartTime = interval.start;
        }

        if (fadeOutStartTime == -1) return trackDuration;
        else return fadeOutStartTime;
    }

    /**
     * @return Whether there is any possibility of a fade out.
     */
    public boolean hasAnyFadeOut() {
        return !fadeOutMap.isEmpty();
    }

    /**
     * @return The amount of fade overlap accumulated during playback.
     */
    public int fadeOverlap() {
        return fadeOverlap;
    }

    /**
     * @return The content that should be played next for custom fade in/out to apply.
     */
    @Nullable
    public PlayableId fadeOutPlayable() {
        return fadeOutPlayable;
    }

    /**
     * An interval without a start. Used when crossfading due to an user interaction.
     */
    public static class PartialFadeInterval extends FadeInterval {
        private int partialStart = -1;

        PartialFadeInterval(int duration, @NotNull GainInterpolator interpolator) {
            super(-1, duration, interpolator);
        }

        @Override
        public int start() {
            if (partialStart == -1) throw new IllegalStateException();
            return partialStart;
        }

        public int end(int now) {
            partialStart = now;
            return end();
        }

        @Override
        public int end() {
            if (partialStart == -1) throw new IllegalStateException();
            return partialStart + duration;
        }

        @Override
        float interpolate(int trackPos) {
            if (partialStart == -1) throw new IllegalStateException();
            return super.interpolate(trackPos - 1 - partialStart);
        }

        @Override
        public String toString() {
            return "PartialFadeInterval{duration=" + duration + ", interpolator=" + interpolator + '}';
        }
    }

    /**
     * An interval representing when the fade should start, end, how much should last and how should behave.
     */
    public static class FadeInterval {
        final int start;
        final int duration;
        final GainInterpolator interpolator;

        FadeInterval(int start, int duration, @NotNull GainInterpolator interpolator) {
            this.start = start;
            this.duration = duration;
            this.interpolator = interpolator;
        }

        public int end() {
            return start + duration;
        }

        public int duration() {
            return duration;
        }

        public int start() {
            return start;
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
