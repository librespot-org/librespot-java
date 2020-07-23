package xyz.gianlu.librespot.player.metrics;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.core.EventService;

/**
 * @author devgianlu
 */
public final class TrackTransitionEvent implements EventService.GenericEvent {
    private static int trackTransitionIncremental = 0;
    private final String deviceId;
    private final String lastCommandSentByDeviceId;
    private final PlaybackMetrics metrics;

    public TrackTransitionEvent(@NotNull String deviceId, @NotNull String lastCommandSentByDeviceId, @NotNull PlaybackMetrics metrics) {
        this.deviceId = deviceId;
        this.lastCommandSentByDeviceId = lastCommandSentByDeviceId;
        this.metrics = metrics;
    }

    @Override
    public EventService.@NotNull EventBuilder build() {
        if (metrics.player.contentMetrics == null)
            throw new IllegalStateException();

        int when = metrics.lastValue();
        EventService.EventBuilder event = new EventService.EventBuilder(EventService.Type.TRACK_TRANSITION);
        event.append(String.valueOf(trackTransitionIncremental++));
        event.append(deviceId);
        event.append(metrics.playbackId).append("00000000000000000000000000000000");
        event.append(metrics.sourceStart).append(metrics.reasonStart == null ? null : metrics.reasonStart.val);
        event.append(metrics.sourceEnd).append(metrics.reasonEnd == null ? null : metrics.reasonEnd.val);
        event.append(String.valueOf(metrics.player.decodedLength)).append(String.valueOf(metrics.player.size));
        event.append(String.valueOf(when)).append(String.valueOf(when));
        event.append(String.valueOf(metrics.player.duration));
        event.append(String.valueOf(metrics.player.decryptTime)).append(String.valueOf(metrics.player.fadeOverlap)).append('0').append('0');
        event.append(metrics.firstValue() == 0 ? '0' : '1').append(String.valueOf(metrics.firstValue()));
        event.append('0').append("-1").append("context");
        event.append(String.valueOf(metrics.player.contentMetrics.audioKeyTime)).append('0');
        event.append(metrics.player.contentMetrics.preloadedAudioKey ? '1' : '0').append('0').append('0').append('0');
        event.append(String.valueOf(when)).append(String.valueOf(when));
        event.append('0').append(String.valueOf(metrics.player.bitrate));
        event.append(metrics.contextUri).append(metrics.player.encoding);
        event.append(metrics.id.hexId()).append("");
        event.append('0').append(String.valueOf(metrics.timestamp)).append('0');
        event.append("context").append(metrics.referrerIdentifier).append(metrics.featureVersion);
        event.append("com.spotify").append(metrics.player.transition).append("none");
        event.append(lastCommandSentByDeviceId).append("na").append("none");
        return event;
    }
}
