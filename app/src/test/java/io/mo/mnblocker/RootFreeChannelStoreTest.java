package io.mo.mnblocker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

public final class RootFreeChannelStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File file() {
        return new File(tmp.getRoot(), "rootfree_channels.json");
    }

    @Test
    public void recordThenReadRoundTrips() {
        File f = file();
        RootFreeChannelStore store = new RootFreeChannelStore(f);
        store.record(new ChannelRecord("pkg.a", "ch1", "促销", "desc", 3, true, 100L));

        List<ChannelRecord> all = RootFreeChannelStore.readAll(f);
        assertEquals(1, all.size());
        assertEquals("pkg.a", all.get(0).pkg);
        assertEquals("ch1", all.get(0).id);
        assertTrue(all.get(0).regexMatched);
    }

    @Test
    public void recordUpdatesExistingKeyInPlace() {
        File f = file();
        RootFreeChannelStore store = new RootFreeChannelStore(f);
        store.record(new ChannelRecord("pkg.a", "ch1", "old", "", 3, false, 100L));
        store.record(new ChannelRecord("pkg.a", "ch1", "new", "", 3, true, 200L));

        List<ChannelRecord> all = RootFreeChannelStore.readAll(f);
        assertEquals(1, all.size());
        assertEquals("new", all.get(0).name);
    }

    @Test
    public void repeatPostWithoutSubstantiveChangeDoesNotRewrite() {
        File f = file();
        RootFreeChannelStore store = new RootFreeChannelStore(f);
        store.record(new ChannelRecord("pkg.a", "ch1", "促销", "d", 3, true, 100L));
        // Same channel, same importance/name/desc/verdict — only the timestamp
        // moved. The full-array rewrite is skipped, so the persisted lastSeen must
        // still be the original 100L, not 200L.
        store.record(new ChannelRecord("pkg.a", "ch1", "促销", "d", 3, true, 200L));

        List<ChannelRecord> all = RootFreeChannelStore.readAll(f);
        assertEquals(1, all.size());
        assertEquals(100L, all.get(0).lastSeen);
    }

    @Test
    public void survivesReloadAcrossInstances() {
        File f = file();
        new RootFreeChannelStore(f).record(new ChannelRecord("pkg.a", "ch1", "n", "", 3, false, 1L));

        // A fresh instance (simulating a process restart) must not wipe the
        // previously persisted entry on its first record().
        RootFreeChannelStore second = new RootFreeChannelStore(f);
        second.record(new ChannelRecord("pkg.b", "ch2", "n2", "", 3, false, 2L));

        List<ChannelRecord> all = RootFreeChannelStore.readAll(f);
        assertEquals(2, all.size());
    }

    @Test
    public void clearEmptiesTheFile() {
        File f = file();
        new RootFreeChannelStore(f).record(new ChannelRecord("pkg.a", "ch1", "n", "", 3, false, 1L));
        assertTrue(RootFreeChannelStore.clear(f));
        assertEquals(0, RootFreeChannelStore.readAll(f).size());
    }

    @Test
    public void readAllOnMissingFileReturnsEmptyList() {
        assertEquals(0, RootFreeChannelStore.readAll(file()).size());
    }

    @Test
    public void trimsToMaxEntriesEvictingOldestFirst() {
        File f = file();
        RootFreeChannelStore store = new RootFreeChannelStore(f);
        for (int i = 0; i < 1005; i++) {
            store.record(new ChannelRecord("pkg", "ch" + i, "n", "", 3, false, i));
        }
        List<ChannelRecord> all = RootFreeChannelStore.readAll(f);
        assertEquals(1000, all.size());
        // The first 5 inserted (ch0..ch4) must have been evicted; the most
        // recent one must survive.
        assertEquals("ch1004", all.get(all.size() - 1).id);
        for (ChannelRecord r : all) {
            assertTrue(!"ch0".equals(r.id) && !"ch4".equals(r.id));
        }
    }
}
