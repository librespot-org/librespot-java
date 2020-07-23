package xyz.gianlu.librespot.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.ZeroconfServer;
import xyz.gianlu.librespot.core.Session;

import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Gianlu
 */
public final class SessionWrapper {
    private final AtomicReference<Session> ref = new AtomicReference<>(null);
    private Listener listener = null;

    private SessionWrapper() {
    }

    /**
     * Convenience method to create an instance of {@link SessionWrapper} that is updated by {@link ZeroconfServer}
     *
     * @param server The {@link ZeroconfServer}
     * @return A wrapper that holds a changing session
     */
    @NotNull
    public static SessionWrapper fromZeroconf(@NotNull ZeroconfServer server) {
        SessionWrapper wrapper = new SessionWrapper();
        server.addSessionListener(wrapper::set);
        return wrapper;
    }

    /**
     * Convenience method to create an instance of {@link SessionWrapper} that holds a static session
     *
     * @param session The static session
     * @return A wrapper that holds a never-changing session
     */
    @NotNull
    public static SessionWrapper fromSession(@NotNull Session session) {
        SessionWrapper wrapper = new SessionWrapper();
        wrapper.ref.set(session);
        return wrapper;
    }

    public void setListener(@NotNull Listener listener) {
        this.listener = listener;

        Session s;
        if ((s = ref.get()) != null) listener.onNewSession(s);
    }

    private void set(@NotNull Session session) {
        ref.set(session);
        session.addCloseListener(this::clear);
        if (listener != null) listener.onNewSession(session);
    }

    private void clear() {
        Session old = ref.get();
        ref.set(null);
        if (listener != null && old != null) listener.onSessionCleared(old);
    }

    @Nullable
    public Session get() {
        Session s = ref.get();
        if (s != null) {
            if (s.isValid()) return s;
            else clear();
        }

        return null;
    }

    public interface Listener {
        void onSessionCleared(@NotNull Session old);

        void onNewSession(@NotNull Session session);
    }
}
