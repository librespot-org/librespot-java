package xyz.gianlu.librespot.common;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ThreadFactory;

/**
 * @author Gianlu
 */
public final class NameThreadFactory implements ThreadFactory {
    private final ThreadGroup group;
    private final NameProvider nameProvider;

    public NameThreadFactory(@NotNull NameProvider nameProvider) {
        this.nameProvider = nameProvider;
        SecurityManager s = System.getSecurityManager();
        group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
    }

    @Override
    public Thread newThread(@NotNull Runnable r) {
        Thread t = new Thread(group, r, nameProvider.getName(r), 0);
        if (t.isDaemon()) t.setDaemon(false);
        if (t.getPriority() != Thread.NORM_PRIORITY) t.setPriority(Thread.NORM_PRIORITY);
        return t;
    }

    public interface NameProvider {
        @NotNull
        String getName(@NotNull Runnable r);
    }
}
