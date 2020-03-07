package xyz.gianlu.librespot.cache;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.gianlu.librespot.common.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Gianlu
 */

class CacheTest {
    private String lastContent = null;
    private File parent;

    private void printJournalFileContent() throws IOException {
        File file = new File(parent, "journal.dat");
        StringBuilder builder = new StringBuilder();
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1)
                builder.append(Utils.bytesToHex(buffer, 0, count));
        }

        System.out.println(">> " + (lastContent = builder.toString()));
    }

    private void assertSequence(int start, String seq) {
        if (lastContent == null) throw new IllegalStateException();

        if (!lastContent.substring(start).startsWith(seq))
            Assertions.fail(String.format("Sequence doesn't match, wanted '%s', but is '%s'!", seq, lastContent.substring(start)));
    }

    void testChunks(CacheJournal journal) throws IOException {
        System.out.println("\n======== CHUNKS ========");

        final String ID = "ABCDEFG";
        journal.createIfNeeded(ID);
        printJournalFileContent();

        journal.setChunk(ID, 0, true);
        printJournalFileContent();
        assertSequence(80, "01000000");

        journal.setChunk(ID, 1, true);
        printJournalFileContent();
        assertSequence(80, "03000000");

        journal.setChunk(ID, 5, true);
        printJournalFileContent();
        assertSequence(80, "23000000");
        assertTrue(journal.hasChunk(ID, 5));

        journal.setChunk(ID, 8, true);
        printJournalFileContent();
        assertSequence(80, "23010000");
        assertTrue(journal.hasChunk(ID, 8));

        journal.setChunk(ID, 5, false);
        printJournalFileContent();
        assertSequence(80, "03010000");
        assertFalse(journal.hasChunk(ID, 5));

        journal.setChunk(ID, 2048 * 8 - 1, true);
        printJournalFileContent();
        assertSequence(4172, "0080000000");
        assertTrue(journal.hasChunk(ID, 2048 * 8 - 1));

        journal.setChunk(ID, 2048 * 8 - 2, true);
        printJournalFileContent();
        assertSequence(4172, "00C0000000");
        assertTrue(journal.hasChunk(ID, 2048 * 8 - 1));
        assertTrue(journal.hasChunk(ID, 2048 * 8 - 2));

        journal.remove(ID);
    }

    private void testCreateRemove(CacheJournal journal) throws IOException {
        System.out.println("\n======== CREATE / REMOVE ========");
        journal.createIfNeeded("AAAAAA");
        printJournalFileContent();
        assertSequence(0, "4141414141410000");

        journal.createIfNeeded("BBBBBB");
        printJournalFileContent();
        assertSequence(18472 * 2, "4242424242420000");

        List<String> entries = journal.getEntries();
        System.out.println(entries);
        assertEquals(2, entries.size());
        assertEquals("AAAAAA", entries.get(0));
        assertEquals("BBBBBB", entries.get(1));

        journal.remove("AAAAAA");
        printJournalFileContent();
        assertSequence(0, "00");
        assertSequence(18472 * 2, "4242424242420000");

        journal.remove("BBBBBB");
        printJournalFileContent();
        assertSequence(0, "00");
        assertSequence(18472 * 2, "00");
    }

    private void testHeaders(CacheJournal journal) throws IOException {
        System.out.println("\n======== HEADERS ========");

        final String ID = "ASDFGHJ";
        journal.createIfNeeded(ID);
        printJournalFileContent();

        journal.setHeader(ID, (byte) 0b00000001, "test".getBytes());
        printJournalFileContent();
        assertSequence(4176, "017465737400");

        journal.setHeader(ID, (byte) 0b10000001, "anotherTest".getBytes());
        printJournalFileContent();
        assertSequence(4176, "017465737400");
        assertSequence(4176 + 1024 * 2, "81616E6F746865725465737400");

        List<JournalHeader> headers = journal.getHeaders(ID);
        System.out.println(headers);
        assertEquals(2, headers.size());

        assertEquals(headers.get(0).id, (byte) 0b00000001);
        assertArrayEquals("test".getBytes(), headers.get(0).value);

        assertEquals(headers.get(1).id, (byte) 0b10000001);
        assertArrayEquals("anotherTest".getBytes(), headers.get(1).value);

        journal.remove(ID);
    }

    @Test
    void testCache(@TempDir File parent) throws IOException {
        try (CacheJournal journal = new CacheJournal(this.parent = parent)) {
            testCreateRemove(journal);
            testChunks(journal);
            testHeaders(journal);
        }
    }
}
