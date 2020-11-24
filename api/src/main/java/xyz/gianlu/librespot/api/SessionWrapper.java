package xyz.gianlu.librespot.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.ZeroconfServer;
import xyz.gianlu.librespot.core.Session;

import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Gianlu
 */
public class SessionWrapper {
    protected final AtomicReference<Session> sessionRef = new AtomicReference<>(null);
    private Listener listener = null;

    protected SessionWrapper() {
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
        server.addSessionListener(new ZeroconfServer.SessionListener() {
            @Override
            public void sessionClosing(@NotNull Session session) {
                if (wrapper.getSession() == session)
                    wrapper.clear();
            }

            @Override
            public void sessionChanged(@NotNull Session session) {
                wrapper.set(session);
            }
        });
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
        wrapper.sessionRef.set(session);
        return wrapper;
    }

    public void setListener(@NotNull Listener listener) {
        this.listener = listener;

        Session s;
        if ((s = sessionRef.get()) != null) listener.onNewSession(s);
    }

    protected void set(@NotNull Session session) {
        sessionRef.set(session);
        session.addCloseListener(this::clear);
        if (listener != null) listener.onNewSession(session);
    }

    protected void clear() {
        Session old = sessionRef.get();
        sessionRef.set(null);
        if (listener != null && old != null) listener.onSessionCleared(old);
    }

    @Nullable
    public Session getSession() {
        Session s = sessionRef.get();
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
