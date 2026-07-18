package io.mo.mnblocker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public final class RootFreeStatsStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File file() {
        return new File(tmp.getRoot(), "rootfree_stats.json");
    }

    @Test
    public void recordBlockAccumulatesCountAndPerApp() {
        File f = file();
        RootFreeStatsStore store = new RootFreeStatsStore(f);
        store.recordBlock("pkg.a");
        store.recordBlock("pkg.a");
        store.recordBlock("pkg.b");

        ContentStatsStore.Snapshot s = RootFreeStatsStore.readFromDisk(f);
        assertEquals(3L, s.count);
        assertEquals(Long.valueOf(2L), s.perApp.get("pkg.a"));
        assertEquals(Long.valueOf(1L), s.perApp.get("pkg.b"));
        assertTrue(s.lastBlocked > 0L);
    }

    @Test
    public void survivesReloadAcrossInstances() {
        File f = file();
        new RootFreeStatsStore(f).recordBlock("pkg.a");
        new RootFreeStatsStore(f).recordBlock("pkg.a");

        ContentStatsStore.Snapshot s = RootFreeStatsStore.readFromDisk(f);
        assertEquals(2L, s.count);
        assertEquals(Long.valueOf(2L), s.perApp.get("pkg.a"));
    }

    @Test
    public void resetZeroesCountsAndPerApp() {
        File f = file();
        RootFreeStatsStore store = new RootFreeStatsStore(f);
        store.recordBlock("pkg.a");
        assertTrue(RootFreeStatsStore.reset(f));

        ContentStatsStore.Snapshot s = RootFreeStatsStore.readFromDisk(f);
        assertEquals(0L, s.count);
        assertEquals(0L, s.lastBlocked);
        assertTrue(s.perApp.isEmpty());
    }

    @Test
    public void readFromDiskOnMissingFileReturnsZeroed() {
        ContentStatsStore.Snapshot s = RootFreeStatsStore.readFromDisk(file());
        assertEquals(0L, s.count);
        assertTrue(s.perApp.isEmpty());
    }
}
