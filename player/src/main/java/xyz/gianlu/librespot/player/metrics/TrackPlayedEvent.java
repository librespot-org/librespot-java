package xyz.gianlu.librespot.player.metrics;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.core.EventService;
import xyz.gianlu.librespot.metadata.PlayableId;

import java.util.List;

/**
 * @author devgianlu
 */
public final class TrackPlayedEvent implements EventService.GenericEvent {
    private final String playbackId;
    private final PlayableId content;
    private final List<PlaybackMetrics.Interval> intervals;

    public TrackPlayedEvent(@NotNull String playbackId, @NotNull PlayableId content, @NotNull List<PlaybackMetrics.Interval> intervals) {
        this.playbackId = playbackId;
        this.content = content;
        this.intervals = intervals;
    }

    @NotNull
    private static String intervalsToSend(@NotNull List<PlaybackMetrics.Interval> intervals) {
        StringBuilder builder = new StringBuilder();
        builder.append('[');

        boolean first = true;
        for (PlaybackMetrics.Interval interval : intervals) {
            if (interval.begin == -1 || interval.end == -1)
                continue;

            if (!first) builder.append(',');
            builder.append('[').append(interval.begin).append(',').append(interval.end).append(']');
            first = false;
        }

        builder.append(']');
        return builder.toString();
    }

    @Override
    public EventService.@NotNull EventBuilder build() {
        EventService.EventBuilder event = new EventService.EventBuilder(EventService.Type.TRACK_PLAYED);
        event.append(playbackId).append(content.toSpotifyUri());
        event.append('0').append(intervalsToSend(intervals));
        return event;
    }
}
