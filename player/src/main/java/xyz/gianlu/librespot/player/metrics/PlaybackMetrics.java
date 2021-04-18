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

package xyz.gianlu.librespot.player.metrics;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.TimeProvider;
import xyz.gianlu.librespot.metadata.PlayableId;
import xyz.gianlu.librespot.player.StateWrapper;
import xyz.gianlu.librespot.player.state.DeviceStateHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * @author devgianlu
 */
public class PlaybackMetrics {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlaybackMetrics.class);
    public final PlayableId id;
    final String playbackId;
    final String featureVersion;
    final String referrerIdentifier;
    final String contextUri;
    final long timestamp;
    private final List<Interval> intervals = new ArrayList<>(10);
    PlayerMetrics player = null;
    Reason reasonStart = null;
    String sourceStart = null;
    Reason reasonEnd = null;
    String sourceEnd = null;
    private Interval lastInterval = null;

    public PlaybackMetrics(@NotNull PlayableId id, @NotNull String playbackId, @NotNull StateWrapper state) {
        this.id = id;
        this.playbackId = playbackId;
        this.contextUri = state.getContextUri();
        this.featureVersion = state.getPlayOrigin().getFeatureVersion();
        this.referrerIdentifier = state.getPlayOrigin().getReferrerIdentifier();
        this.timestamp = TimeProvider.currentTimeMillis();
    }

    int firstValue() {
        if (intervals.isEmpty()) return 0;
        else return intervals.get(0).begin;
    }

    int lastValue() {
        if (intervals.isEmpty()) return player == null ? 0 : player.duration;
        else return intervals.get(intervals.size() - 1).end;
    }

    public void startInterval(int begin) {
        lastInterval = new Interval(begin);
    }

    public void endInterval(int end) {
        if (lastInterval == null) return;
        if (lastInterval.begin == end) {
            lastInterval = null;
            return;
        }

        lastInterval.end = end;
        intervals.add(lastInterval);
        lastInterval = null;
    }

    public void startedHow(@NotNull Reason reason, @Nullable String origin) {
        reasonStart = reason;
        sourceStart = origin == null || origin.isEmpty() ? "unknown" : origin;
    }

    public void endedHow(@NotNull Reason reason, @Nullable String origin) {
        reasonEnd = reason;
        sourceEnd = origin == null || origin.isEmpty() ? "unknown" : origin;
    }

    public void update(@Nullable PlayerMetrics playerMetrics) {
        player = playerMetrics;
    }

    public void sendEvents(@NotNull Session session, @NotNull DeviceStateHandler device) {
        if (player == null || player.contentMetrics == null || device.getLastCommandSentByDeviceId() == null) {
            LOGGER.warn("Did not send event because of missing metrics: " + playbackId);
            return;
        }

        session.eventService().sendEvent(new TrackTransitionEvent(session.deviceId(), device.getLastCommandSentByDeviceId(), this));
    }

    public enum Reason {
        TRACK_DONE("trackdone"), TRACK_ERROR("trackerror"),
        FORWARD_BTN("fwdbtn"), BACK_BTN("backbtn"),
        END_PLAY("endplay"), PLAY_BTN("playbtn"), CLICK_ROW("clickrow"),
        LOGOUT("logout"), APP_LOAD("appload"), REMOTE("remote");

        final String val;

        Reason(@NotNull String val) {
            this.val = val;
        }
    }

    static class Interval {
        final int begin;
        int end = -1;

        private Interval(int begin) {
            this.begin = begin;
        }
    }
}
