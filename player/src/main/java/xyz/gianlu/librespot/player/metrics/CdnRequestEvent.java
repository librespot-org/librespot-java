package xyz.gianlu.librespot.player.metrics;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.core.EventService;

/**
 * @author devgianlu
 */
public final class CdnRequestEvent implements EventService.GenericEvent {
    private final PlayerMetrics playerMetrics;
    private final String playbackId;

    public CdnRequestEvent(@NotNull PlayerMetrics playerMetrics, @NotNull String playbackId) {
        this.playerMetrics = playerMetrics;
        this.playbackId = playbackId;
    }

    @Override
    public EventService.@NotNull EventBuilder build() {
        if (playerMetrics.contentMetrics == null)
            throw new IllegalStateException();

        EventService.EventBuilder event = new EventService.EventBuilder(EventService.Type.CDN_REQUEST);
        event.append(playerMetrics.contentMetrics.fileId).append(playbackId);
        event.append('0').append('0').append('0').append('0').append('0').append('0');
        event.append(String.valueOf(playerMetrics.decodedLength)).append(String.valueOf(playerMetrics.size));
        event.append("music").append("-1").append("-1").append("-1").append("-1.000000");
        event.append("-1").append("-1.000000").append("-1").append("-1").append("-1").append("-1.000000");
        event.append("-1").append("-1").append("-1").append("-1").append("-1.000000").append("-1");
        event.append("0.000000").append("-1.000000").append("").append("").append("unknown");
        event.append('0').append('0').append('0').append('0').append('0');
        event.append("interactive").append('0').append(String.valueOf(playerMetrics.bitrate)).append('0').append('0');
        return event;
    }
}
