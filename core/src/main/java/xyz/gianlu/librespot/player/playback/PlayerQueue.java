package xyz.gianlu.librespot.player.playback;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.NameThreadFactory;

import java.io.Closeable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles the queue of entries. Responsible for next/prev operations and executing each entry on the executor.
 *
 * @author devgianlu
 */
final class PlayerQueue implements Closeable {
    private static final Logger LOGGER = LogManager.getLogger(PlayerQueue.class);
    private final ExecutorService executorService = Executors.newCachedThreadPool(new NameThreadFactory((r) -> "player-queue-" + r.hashCode()));
    private PlayerQueueEntry head = null;

    PlayerQueue() {
    }

    /**
     * @return The next element after the head
     */
    @Nullable
    @Contract(pure = true)
    synchronized PlayerQueueEntry next() {
        if (head == null || head.next == null) return null;
        else return head.next;
    }

    /**
     * @return The head of the queue
     */
    @Nullable
    @Contract(pure = true)
    synchronized PlayerQueueEntry head() {
        return head;
    }

    /**
     * @return The next element after the head
     */
    @Nullable
    @Contract(pure = true)
    synchronized PlayerQueueEntry prev() {
        if (head == null || head.prev == null) return null;
        else return head.prev;
    }

    /**
     * Adds an entry to the queue and executes it.
     *
     * @param entry The entry to add
     */
    synchronized void add(@NotNull PlayerQueueEntry entry) {
        if (head == null) head = entry;
        else head.setNext(entry);
        executorService.execute(entry);

        LOGGER.trace("{} added to queue.", entry);
    }

    /**
     * Swap two entries, closing the old one in any case.
     *
     * @param oldEntry The old entry
     * @param newEntry The new entry
     */
    synchronized void swap(@NotNull PlayerQueueEntry oldEntry, @NotNull PlayerQueueEntry newEntry) {
        if (head == null) return;

        boolean swapped;
        if (head == oldEntry) {
            head = newEntry;
            head.next = oldEntry.next;
            head.prev = oldEntry.prev;
            swapped = true;
        } else {
            swapped = head.swap(oldEntry, newEntry);
        }

        oldEntry.close();
        if (swapped) {
            executorService.execute(newEntry);
            LOGGER.trace("{} swapped with {}.", oldEntry, newEntry);
        }
    }

    /**
     * Removes the specified entry from the queue and closes it.
     *
     * @param entry The entry to remove
     */
    synchronized void remove(@NotNull PlayerQueueEntry entry) {
        if (head == null) return;

        boolean removed;
        if (head == entry) {
            PlayerQueueEntry tmp = head;
            head = tmp.next;
            tmp.close();
            removed = true;
        } else {
            removed = head.remove(entry);
        }

        if (removed) LOGGER.trace("{} removed from queue.", entry);
    }

    /**
     * Tries to advance in the queue.
     *
     * @return If the operation was successful. If {@code true} the head will surely be non-null, always {@code null} otherwise.
     */
    synchronized boolean advance() {
        if (head == null || head.next == null)
            return false;

        PlayerQueueEntry tmp = head.next;
        head.next = null;
        head.prev = null;
        if (!head.closeIfUseless()) tmp.prev = head;
        head = tmp;
        return true;
    }

    /**
     * Clear the queue by closing every entry and shutdown the executor service.
     */
    @Override
    public void close() {
        if (head != null) head.clear();
        executorService.shutdown();

        LOGGER.trace("Queue has been cleared.");
    }

    abstract static class Entry {
        PlayerQueueEntry next = null;
        PlayerQueueEntry prev = null;

        void setNext(@NotNull PlayerQueueEntry entry) {
            if (next == null) {
                next = entry;
                entry.prev = (PlayerQueueEntry) this;
            } else {
                next.setNext(entry);
            }
        }

        boolean remove(@NotNull PlayerQueueEntry entry) {
            if (next == null) return false;
            if (next == entry) {
                PlayerQueueEntry tmp = next;
                next = tmp.next;
                tmp.close();
                return true;
            } else {
                return next.remove(entry);
            }
        }

        boolean swap(@NotNull PlayerQueueEntry oldEntry, @NotNull PlayerQueueEntry newEntry) {
            if (next == null) return false;
            if (next == oldEntry) {
                next = newEntry;
                next.prev = oldEntry.prev;
                next.next = oldEntry.next;
                return true;
            } else {
                return next.swap(oldEntry, newEntry);
            }
        }

        void clear() {
            if (prev != null) {
                prev.clear();
                prev.close();
                prev = null;
            }

            if (next != null) {
                next.clear();
                next.close();
                next = null;
            }

            ((PlayerQueueEntry) this).close();
        }
    }
}
