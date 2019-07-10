package xyz.gianlu.librespot.connectstate;

import com.spotify.connectstate.model.Player;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.player.contexts.AbsSpotifyContext;

/**
 * @author Gianlu
 */
public final class RestrictionsManager {
    private final Player.Restrictions.Builder restrictions;

    public RestrictionsManager(@NotNull AbsSpotifyContext<?> context) {
        restrictions = Player.Restrictions.newBuilder();

        if (!context.isFinite()) {
            disallow(Action.SHUFFLE, "infinite-context"); // FIXME: Proper name?
            disallow(Action.REPEAT_CONTEXT, "infinite-context");
        }
    }

    @NotNull
    public synchronized Player.Restrictions toProto() {
        return restrictions.build();
    }

    public synchronized boolean can(@NotNull Action action) {
        switch (action) {
            case SHUFFLE:
                return restrictions.getDisallowTogglingShuffleReasonsCount() == 0;
            case REPEAT_CONTEXT:
                return restrictions.getDisallowTogglingRepeatContextReasonsCount() == 0;
            case REPEAT_TRACK:
                return restrictions.getDisallowTogglingRepeatTrackReasonsCount() == 0;
            default:
                throw new IllegalArgumentException("Unknown restriction for " + action);
        }
    }

    public synchronized void allow(@NotNull Action action) {
        switch (action) {
            case SHUFFLE:
                restrictions.clearDisallowTogglingShuffleReasons();
                break;
            case REPEAT_CONTEXT:
                restrictions.clearDisallowTogglingRepeatContextReasons();
                break;
            case REPEAT_TRACK:
                restrictions.clearDisallowTogglingRepeatTrackReasons();
                break;
            case PAUSE:
                restrictions.clearDisallowPausingReasons();
                break;
            case RESUME:
                restrictions.clearDisallowResumingReasons();
                break;
            case SEEK:
                restrictions.clearDisallowSeekingReasons();
                break;
            case SKIP_PREV:
                restrictions.clearDisallowSkippingPrevReasons();
                break;
            case SKIP_NEXT:
                restrictions.clearDisallowSkippingNextReasons();
                break;
        }
    }

    public synchronized void disallow(@NotNull Action action, @NotNull String reason) {
        allow(action);

        switch (action) {
            case SHUFFLE:
                restrictions.addDisallowTogglingShuffleReasons(reason);
                break;
            case REPEAT_CONTEXT:
                restrictions.addDisallowTogglingRepeatContextReasons(reason);
                break;
            case REPEAT_TRACK:
                restrictions.addDisallowTogglingRepeatTrackReasons(reason);
                break;
            case PAUSE:
                restrictions.addDisallowPausingReasons(reason);
                break;
            case RESUME:
                restrictions.addDisallowResumingReasons(reason);
                break;
            case SEEK:
                restrictions.addDisallowSeekingReasons(reason);
                break;
            case SKIP_PREV:
                restrictions.addDisallowSkippingPrevReasons(reason);
                break;
            case SKIP_NEXT:
                restrictions.addDisallowSkippingNextReasons(reason);
                break;
        }
    }

    public enum Action {
        SHUFFLE, REPEAT_CONTEXT, REPEAT_TRACK, PAUSE, RESUME, SEEK, SKIP_PREV, SKIP_NEXT
    }
}
