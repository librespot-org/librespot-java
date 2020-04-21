package xyz.gianlu.librespot.common;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ThreadFactory;
import java.util.function.Function;

/**
 * @author Gianlu
 */
public final class NameThreadFactory implements ThreadFactory {
    private final ThreadGroup group;
    private final Function<Runnable, String> nameProvider;

    public NameThreadFactory(@NotNull Function<Runnable, String> nameProvider) {
        this.nameProvider = nameProvider;
        SecurityManager s = System.getSecurityManager();
        group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
    }

    @Override
    public @NotNull Thread newThread(@NotNull Runnable r) {
        Thread t = new Thread(group, r, nameProvider.apply(r), 0);
        if (t.isDaemon()) t.setDaemon(false);
        if (t.getPriority() != Thread.NORM_PRIORITY) t.setPriority(Thread.NORM_PRIORITY);
        return t;
    }
}
