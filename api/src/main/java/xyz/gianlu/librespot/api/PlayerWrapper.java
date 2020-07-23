package xyz.gianlu.librespot.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.ZeroconfServer;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.player.Player;
import xyz.gianlu.librespot.player.PlayerConfiguration;

import java.util.concurrent.atomic.AtomicReference;

/**
 * @author devgianlu
 */
public class PlayerWrapper extends SessionWrapper {
    private final AtomicReference<Player> playerRef = new AtomicReference<>(null);
    private final PlayerConfiguration conf;
    private Listener listener = null;

    private PlayerWrapper(@NotNull PlayerConfiguration conf) {
        this.conf = conf;
    }

    /**
     * Convenience method to create an instance of {@link PlayerWrapper} that is updated by {@link ZeroconfServer}
     *
     * @param server The {@link ZeroconfServer}
     * @param conf   The player configuration
     * @return A wrapper that holds a changing session-player tuple
     */
    @NotNull
    public static PlayerWrapper fromZeroconf(@NotNull ZeroconfServer server, @NotNull PlayerConfiguration conf) {
        PlayerWrapper wrapper = new PlayerWrapper(conf);
        server.addSessionListener(wrapper::set);
        return wrapper;
    }

    /**
     * Convenience method to create an instance of {@link PlayerWrapper} that holds a static session and player
     *
     * @param session The static session
     * @param conf    The player configuration
     * @return A wrapper that holds a never-changing session-player tuple
     */
    @NotNull
    public static PlayerWrapper fromSession(@NotNull Session session, @NotNull PlayerConfiguration conf) {
        PlayerWrapper wrapper = new PlayerWrapper(conf);
        wrapper.sessionRef.set(session);

        Player player = new Player(conf, session);
        player.initState();
        wrapper.playerRef.set(player);

        return wrapper;
    }

    public void setListener(@NotNull Listener listener) {
        super.setListener(listener);
        this.listener = listener;

        Player p;
        if ((p = playerRef.get()) != null) listener.onNewPlayer(p);
    }

    @Override
    protected void set(@NotNull Session session) {
        super.set(session);

        Player player = new Player(conf, session);
        player.initState();
        playerRef.set(player);

        if (listener != null) listener.onNewPlayer(player);
    }

    @Override
    protected void clear() {
        super.clear();

        Player old = playerRef.get();
        if (old != null) old.close();
        playerRef.set(null);

        if (listener != null && old != null) listener.onPlayerCleared(old);
    }

    @Nullable
    public Player getPlayer() {
        return playerRef.get();
    }

    public interface Listener extends SessionWrapper.Listener {
        void onPlayerCleared(@NotNull Player old);

        void onNewPlayer(@NotNull Player player);
    }
}
