package xyz.gianlu.librespot.player.metrics;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.core.EventService;
import xyz.gianlu.librespot.core.TimeProvider;

/**
 * Event structure for a new playback ID.
 *
 * @author devgianlu
 */
public final class NewPlaybackIdEvent implements EventService.GenericEvent {
    private final String sessionId;
    private final String playbackId;

    public NewPlaybackIdEvent(@NotNull String sessionId, @NotNull String playbackId) {
        this.sessionId = sessionId;
        this.playbackId = playbackId;
    }

    @Override
    @NotNull
    public EventService.EventBuilder build() {
        EventService.EventBuilder event = new EventService.EventBuilder(EventService.Type.NEW_PLAYBACK_ID);
        event.append(playbackId).append(sessionId).append(String.valueOf(TimeProvider.currentTimeMillis()));
        return event;
    }
}
