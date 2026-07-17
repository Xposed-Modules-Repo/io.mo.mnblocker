package io.mo.mnblocker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

public final class RootFreeBlockLogStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File file() {
        return new File(tmp.getRoot(), "rootfree_block_log.json");
    }

    @Test
    public void recordThenReadReturnsNewestFirst() {
        File f = file();
        RootFreeBlockLogStore store = new RootFreeBlockLogStore(f);
        store.record("pkg.a", "t1", "x1", "rule1");
        store.record("pkg.a", "t2", "x2", "rule2");

        List<ContentBlockLogStore.Entry> entries = RootFreeBlockLogStore.readForApp(f, "pkg.a");
        assertEquals(2, entries.size());
        assertEquals("t2", entries.get(0).title); // newest first
        assertEquals("t1", entries.get(1).title);
    }

    @Test
    public void readForAppOfUnknownPackageReturnsEmpty() {
        File f = file();
        new RootFreeBlockLogStore(f).record("pkg.a", "t", "x", "r");
        assertTrue(RootFreeBlockLogStore.readForApp(f, "pkg.other").isEmpty());
    }

    @Test
    public void clipsOverlongTextToMaxLen() {
        File f = file();
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            longText.append('x');
        }
        new RootFreeBlockLogStore(f).record("pkg.a", "t", longText.toString(), "r");

        List<ContentBlockLogStore.Entry> entries = RootFreeBlockLogStore.readForApp(f, "pkg.a");
        assertEquals(500, entries.get(0).text.length());
    }

    @Test
    public void trimsToMaxPerAppEvictingOldestFirst() {
        File f = file();
        RootFreeBlockLogStore store = new RootFreeBlockLogStore(f);
        for (int i = 0; i < 105; i++) {
            store.record("pkg.a", "t" + i, "x", "r");
        }
        List<ContentBlockLogStore.Entry> entries = RootFreeBlockLogStore.readForApp(f, "pkg.a");
        assertEquals(100, entries.size());
        assertEquals("t104", entries.get(0).title); // newest first
        assertEquals("t5", entries.get(entries.size() - 1).title);
    }

    @Test
    public void resetClearsTheWholeLog() {
        File f = file();
        RootFreeBlockLogStore store = new RootFreeBlockLogStore(f);
        store.record("pkg.a", "t", "x", "r");
        assertTrue(RootFreeBlockLogStore.reset(f));
        assertTrue(RootFreeBlockLogStore.readForApp(f, "pkg.a").isEmpty());
    }

    @Test
    public void readForAppOnMissingFileReturnsEmpty() {
        assertTrue(RootFreeBlockLogStore.readForApp(file(), "pkg.a").isEmpty());
    }
}
