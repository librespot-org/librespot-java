package xyz.gianlu.librespot.player;

import com.spotify.connectstate.model.Connect;
import com.spotify.connectstate.model.Player.ContextPlayerOptions;
import com.spotify.connectstate.model.Player.PlayerState;
import com.spotify.connectstate.model.Player.Restrictions;
import com.spotify.connectstate.model.Player.Suppressions;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import spotify.player.proto.transfer.TransferStateOuterClass;
import xyz.gianlu.librespot.connectstate.DeviceStateHandler;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.TimeProvider;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.player.contexts.AbsSpotifyContext;

import java.io.IOException;

import static spotify.player.proto.ContextOuterClass.Context;

/**
 * @author Gianlu
 */
public class StateWrapper implements DeviceStateHandler.Listener {
    private static final Logger LOGGER = Logger.getLogger(StateWrapper.class);
    private final PlayerState.Builder state;
    private final Session session;
    private final DeviceStateHandler device;
    private AbsSpotifyContext<?> context;

    StateWrapper(@NotNull Session session) {
        this.session = session;
        this.device = new DeviceStateHandler(session);
        this.state = initState();

        device.addListener(this);
    }

    @NotNull
    private static PlayerState.Builder initState() {
        return PlayerState.newBuilder()
                .setIsSystemInitiated(false)
                .setPlaybackSpeed(1.0)
                .setQueueRevision("0")
                .setSuppressions(Suppressions.newBuilder().build())
                .setRestrictions(Restrictions.newBuilder().build())
                .setContextRestrictions(Restrictions.newBuilder().build())
                .setOptions(ContextPlayerOptions.newBuilder()
                        .setRepeatingContext(false)
                        .setShufflingContext(false)
                        .setRepeatingTrack(false))
                .setPositionAsOfTimestamp(0)
                .setIsPlaying(false);
    }

    void setState(boolean playing, boolean paused, boolean buffering) {
        state.setIsPlaying(playing).setIsPaused(paused).setIsBuffering(buffering);
    }

    boolean isPaused() {
        return state.getIsPaused();
    }

    boolean isActuallyPlaying() {
        return state.getIsPlaying() && !state.getIsPaused() && !state.getIsBuffering();
    }

    boolean isLoading() {
        return state.getIsBuffering();
    }

    boolean isShuffling() {
        return state.getOptions().getShufflingContext();
    }

    boolean isRepeatingContext() {
        return state.getOptions().getRepeatingContext();
    }

    boolean isRepeatingTrack() {
        return state.getOptions().getRepeatingTrack();
    }

    @Nullable
    String getContextUri() {
        return state.getContextUri();
    }

    private void setContext(@NotNull Context context) throws AbsSpotifyContext.UnsupportedContextException {
        setContext(context.getUri());
        if (context.hasRestrictions()) this.context.updateRestrictions(context.getRestrictions());
    }

    private void setContext(@NotNull String context) throws AbsSpotifyContext.UnsupportedContextException {
        this.context = AbsSpotifyContext.from(context);
        this.state.setContextUri(context);
    }

    synchronized void updated() {
        updatePosition();
        device.updateState(Connect.PutStateReason.PLAYER_STATE_CHANGED, state.build());
    }

    public void updateVolume(int volume) {
        device.info().setVolume(volume);
        device.updateState(Connect.PutStateReason.VOLUME_CHANGED, state.build());
    }

    public void addListener(@NotNull DeviceStateHandler.Listener listener) {
        device.addListener(listener);
    }

    @Override
    public void ready() {
        device.updateState(Connect.PutStateReason.NEW_DEVICE, state.build());
        LOGGER.info("Notified new device (us)!");
    }

    @Override
    public void command(@NotNull String endpoint, TransferStateOuterClass.@NotNull TransferState cmd) {
    }

    public int getVolume() {
        return device.info().getVolume();
    }

    public int getPosition() {
        int diff = (int) (TimeProvider.currentTimeMillis() - state.getTimestamp());
        return (int) (state.getPosition() + diff);
    }

    public void setPosition(long pos) {
        state.setTimestamp(TimeProvider.currentTimeMillis());
        state.setPositionAsOfTimestamp(pos);
    }

    private void updatePosition() {
        setPosition(getPosition());
    }

    public void transfer(@NotNull TransferStateOuterClass.TransferState cmd) throws MercuryClient.MercuryException, AbsSpotifyContext.UnsupportedContextException, IOException {

    }

    @Nullable
    public PlayableId nextPlayableDoNotSet() {
        return null; // TODO
    }

    @NotNull
    public PlayableId getCurrentPlayable() {
        return null; // TODO
    }

    @NotNull
    public NextPlayable nextPlayable(@NotNull Player.Configuration conf) {
        return NextPlayable.MISSING_TRACKS; // TODO
    }

    @NotNull
    public PreviousPlayable previousPlayable() {
        return PreviousPlayable.MISSING_TRACKS; // TODO
    }

    public enum PreviousPlayable {
        MISSING_TRACKS, OK;

        public boolean isOk() {
            return this == OK;
        }
    }

    public enum NextPlayable {
        MISSING_TRACKS, AUTOPLAY,
        OK_PLAY, OK_PAUSE;

        public boolean isOk() {
            return this == OK_PLAY || this == OK_PAUSE;
        }
    }
}