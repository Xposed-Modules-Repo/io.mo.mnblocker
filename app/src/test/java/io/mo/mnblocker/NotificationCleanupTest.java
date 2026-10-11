package io.mo.mnblocker;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression scenarios for issue #3, including enqueue order and mixed groups. */
public final class NotificationCleanupTest {
    private final AtomicLong clock = new AtomicLong();
    private final NotificationCleanup cleanup = new NotificationCleanup(clock::get);
    private final FakeBackend backend = new FakeBackend();

    private static NotificationCleanup.Id id(int value) {
        return new NotificationCleanup.Id("shop", 0, value, null);
    }

    private static NotificationCleanup.Entry entry(NotificationCleanup.Id id, String group,
                                                    boolean summary, boolean protectedEntry) {
        return new NotificationCleanup.Entry(id, group, summary, protectedEntry, new Object());
    }

    private void block(NotificationCleanup.Id id, String group) {
        cleanup.onEnqueue(id);
        cleanup.onBlocked(backend, id, group, false);
    }

    @Test public void blockedUpdateCancelsExistingPlaceholderOnly() {
        backend.entries.add(entry(id(1), null, false, false));
        backend.entries.add(entry(id(2), null, false, false));
        block(id(1), null);
        backend.runUntil(0);
        assertFalse(backend.contains(id(1)));
        assertTrue(backend.contains(id(2)));
        assertEquals(1, backend.canceled.size());
    }

    @Test public void pendingPlaceholderIsRemovedAfterEarlierEnqueueWork() {
        backend.post(() -> backend.entries.add(entry(id(1), null, false, false)), 0);
        block(id(1), null);
        backend.runUntil(0);
        assertFalse(backend.contains(id(1)));
        assertEquals(1, backend.canceled.size());
    }

    @Test public void blockedChildRemovesPreviouslyPostedSummary() {
        backend.entries.add(entry(id(100), "group", true, false));
        block(id(1), "group");
        backend.runUntil(0);
        assertTrue(backend.contains(id(100)));
        backend.runUntil(NotificationCleanup.SUMMARY_DELAY_MS);
        assertFalse(backend.contains(id(100)));
    }

    @Test public void summaryPostedAfterInitialSweepIsStillRemoved() {
        block(id(1), "group");
        backend.runUntil(500);
        cleanup.onEnqueue(id(100));
        backend.entries.add(entry(id(100), "group", true, false));
        cleanup.onEnqueueFinished(backend);
        backend.runUntil(1000);
        assertFalse(backend.contains(id(100)));
    }

    @Test public void childPostedDuringGracePeriodProtectsSummary() {
        backend.entries.add(entry(id(100), "group", true, false));
        block(id(1), "group");
        backend.post(() -> backend.entries.add(entry(id(2), "group", false, false)), 200);
        backend.runUntil(500);
        assertTrue(backend.contains(id(100)));
        assertTrue(backend.contains(id(2)));
        assertTrue(backend.canceled.isEmpty());
    }

    @Test public void pendingNormalChildAlsoProtectsSummary() {
        backend.entries.add(entry(id(100), "group", true, false));
        // Backend combines posted and enqueued records; this entry represents the pending list.
        backend.entries.add(entry(id(2), "group", false, false));
        block(id(1), "group");
        backend.runUntil(500);
        assertTrue(backend.contains(id(100)));
        assertTrue(backend.canceled.isEmpty());
    }

    @Test public void mixedGroupKeepsNormalChildAndSummary() {
        backend.entries.add(entry(id(100), "group", true, false));
        backend.entries.add(entry(id(1), "group", false, false));
        backend.entries.add(entry(id(2), "group", false, false));
        block(id(1), "group");
        backend.runUntil(500);
        assertFalse(backend.contains(id(1)));
        assertTrue(backend.contains(id(2)));
        assertTrue(backend.contains(id(100)));
        assertEquals(1, backend.canceled.size());
    }

    @Test public void legitimateGroupIsForgottenRatherThanCleanedOnLaterUnrelatedPost() {
        backend.entries.add(entry(id(100), "group", true, false));
        backend.entries.add(entry(id(2), "group", false, false));
        block(id(1), "group");
        backend.runUntil(500);
        backend.entries.removeIf(e -> e.id.equals(id(2))); // later dismissed by user/app
        cleanup.onEnqueueFinished(backend);
        backend.runUntil(1000);
        assertTrue(backend.contains(id(100)));
    }

    @Test public void laterAllowedUpdateSupersedesQueuedCleanup() {
        backend.entries.add(entry(id(1), null, false, false));
        block(id(1), null);
        cleanup.onEnqueue(id(1)); // e.g. order/verification-code update using the same key
        backend.runUntil(0);
        assertTrue(backend.contains(id(1)));
        assertTrue(backend.canceled.isEmpty());
    }

    @Test public void repeatedBlocksDoNotLetOlderTaskCancelTwice() {
        backend.entries.add(entry(id(1), null, false, false));
        block(id(1), null);
        block(id(1), null);
        backend.runUntil(0);
        assertEquals(1, backend.canceled.size());
    }

    @Test public void identitiesAreScopedByPackageUserAndTag() {
        NotificationCleanup.Id otherPkg = new NotificationCleanup.Id("other", 0, 1, null);
        NotificationCleanup.Id otherUser = new NotificationCleanup.Id("shop", 10, 1, null);
        NotificationCleanup.Id otherTag = new NotificationCleanup.Id("shop", 0, 1, "order");
        backend.entries.add(entry(id(1), null, false, false));
        backend.entries.add(entry(otherPkg, null, false, false));
        backend.entries.add(entry(otherUser, null, false, false));
        backend.entries.add(entry(otherTag, null, false, false));
        block(id(1), null);
        backend.runUntil(0);
        assertEquals(3, backend.entries.size());
        assertTrue(backend.contains(otherPkg));
        assertTrue(backend.contains(otherUser));
        assertTrue(backend.contains(otherTag));
    }

    @Test public void groupNamesCannotCrossApplicationsOrUsers() {
        NotificationCleanup.Id otherPkg = new NotificationCleanup.Id("other", 0, 100, null);
        NotificationCleanup.Id otherUser = new NotificationCleanup.Id("shop", 10, 100, null);
        backend.entries.add(entry(id(100), "same", true, false));
        backend.entries.add(entry(otherPkg, "same", true, false));
        backend.entries.add(entry(otherUser, "same", true, false));
        block(id(1), "same");
        backend.runUntil(500);
        assertFalse(backend.contains(id(100)));
        assertTrue(backend.contains(otherPkg));
        assertTrue(backend.contains(otherUser));
    }

    @Test public void oldAutomaticallyGroupedChildProvidesEffectiveGroupKey() {
        backend.entries.add(entry(id(1), "autogroup", false, false));
        backend.entries.add(entry(id(100), "autogroup", true, false));
        block(id(1), null); // incoming app notification has no explicit group
        backend.runUntil(500);
        assertTrue(backend.entries.isEmpty());
        assertEquals(2, backend.canceled.size());
    }

    @Test public void unrelatedEmptyNotificationsAndSummariesRemain() {
        backend.entries.add(entry(id(2), null, false, false));
        backend.entries.add(entry(id(100), "unrelated", true, false));
        block(id(1), "marketing");
        backend.runUntil(500);
        assertEquals(2, backend.entries.size());
        assertTrue(backend.canceled.isEmpty());
    }

    @Test public void foregroundOrWhitelistedOldRecordIsProtected() {
        backend.entries.add(entry(id(1), null, false, true));
        block(id(1), null);
        backend.runUntil(0);
        assertTrue(backend.contains(id(1)));
    }

    @Test public void protectedPendingVersionProtectsEntireIdentity() {
        backend.entries.add(entry(id(1), null, false, false));
        backend.entries.add(entry(id(1), null, false, true));
        block(id(1), null);
        backend.runUntil(0);
        assertEquals(2, backend.entries.size());
        assertTrue(backend.canceled.isEmpty());
    }

    @Test public void protectedSummaryAndForegroundChildSurvive() {
        backend.entries.add(entry(id(100), "group", true, true));
        block(id(1), "group");
        backend.runUntil(500);
        assertTrue(backend.contains(id(100)));
        backend.entries.add(entry(id(2), "group", false, true));
        block(id(3), "group");
        backend.runUntil(1000);
        assertTrue(backend.contains(id(2)));
        assertTrue(backend.contains(id(100)));
    }

    @Test public void protectedPendingSummaryUpdateProtectsPostedVersion() {
        backend.entries.add(entry(id(100), "group", true, false));
        backend.entries.add(entry(id(100), "group", true, true));
        block(id(1), "group");
        backend.runUntil(500);
        assertEquals(2, backend.entries.size());
        assertTrue(backend.canceled.isEmpty());
    }

    @Test public void summaryMovingToAnotherGroupIsNotRemovedWithOldOrphan() {
        backend.entries.add(entry(id(100), "marketing", true, false));
        backend.entries.add(entry(id(100), "orders", true, false)); // pending update, same key
        backend.entries.add(entry(id(2), "orders", false, false));
        block(id(1), "marketing");
        backend.runUntil(500);
        assertEquals(3, backend.entries.size());
        assertTrue(backend.canceled.isEmpty());
    }

    @Test public void blockedSummaryDoesNotCancelExistingValidChildren() {
        backend.entries.add(entry(id(100), "group", true, false));
        backend.entries.add(entry(id(2), "group", false, false));
        cleanup.onBlocked(backend, id(100), "group", true);
        backend.runUntil(500);
        assertEquals(2, backend.entries.size());
        assertTrue(backend.canceled.isEmpty());
    }

    @Test public void disabledModeOrSafetyGateStopsAlreadyQueuedWork() {
        backend.entries.add(entry(id(1), "group", false, false));
        backend.entries.add(entry(id(100), "group", true, false));
        block(id(1), "group");
        backend.enabled = false;
        backend.runUntil(500);
        assertEquals(2, backend.entries.size());
        backend.enabled = true;
        backend.entries.removeIf(e -> e.id.equals(id(1)));
        cleanup.onEnqueueFinished(backend);
        backend.runUntil(1000);
        assertTrue(backend.contains(id(100)));
    }

    @Test public void groupEvidenceExpiresBeforeAnUnrelatedLaterSummary() {
        block(id(1), "group");
        backend.runUntil(NotificationCleanup.GROUP_TTL_MS);
        backend.entries.add(entry(id(100), "group", true, false));
        cleanup.onEnqueueFinished(backend);
        backend.runUntil(NotificationCleanup.GROUP_TTL_MS + 500);
        assertTrue(backend.contains(id(100)));
    }

    @Test public void evidenceIsBoundedAndNewestGroupsStillCleanUp() {
        for (int i = 0; i <= NotificationCleanup.MAX_TRACKED; i++) {
            block(id(i), "g" + i);
        }
        backend.entries.add(entry(id(1000), "g0", true, false));
        backend.entries.add(entry(id(1001), "g" + NotificationCleanup.MAX_TRACKED, true, false));
        backend.runUntil(500);
        assertTrue(backend.contains(id(1000)));
        assertFalse(backend.contains(id(1001)));
    }

    private final class FakeBackend implements NotificationCleanup.Backend {
        final List<NotificationCleanup.Entry> entries = new ArrayList<>();
        final List<NotificationCleanup.Id> canceled = new ArrayList<>();
        final List<Scheduled> tasks = new ArrayList<>();
        boolean enabled = true;
        boolean locked;
        long sequence;

        @Override public boolean isEnabled() { return enabled; }

        @Override public boolean post(Runnable task, long delayMs) {
            tasks.add(new Scheduled(clock.get() + delayMs, sequence++, task));
            return true;
        }

        @Override public void withNotifications(Consumer<List<NotificationCleanup.Entry>> action) {
            assertFalse(locked);
            locked = true;
            try { action.accept(new ArrayList<>(entries)); }
            finally { locked = false; }
        }

        @Override public void cancel(NotificationCleanup.Entry entry) {
            assertTrue("snapshot and cancellation must share the lock", locked);
            canceled.add(entry.id);
            entries.removeIf(e -> entry.id.equals(e.id));
        }

        boolean contains(NotificationCleanup.Id id) {
            return entries.stream().anyMatch(e -> id.equals(e.id));
        }

        void runUntil(long end) {
            while (!tasks.isEmpty()) {
                tasks.sort(Comparator.comparingLong((Scheduled s) -> s.at)
                        .thenComparingLong(s -> s.sequence));
                Scheduled next = tasks.get(0);
                if (next.at > end) break;
                tasks.remove(0);
                clock.set(next.at);
                next.task.run();
            }
            clock.set(end);
        }
    }

    private static final class Scheduled {
        final long at;
        final long sequence;
        final Runnable task;
        Scheduled(long at, long sequence, Runnable task) {
            this.at = at;
            this.sequence = sequence;
            this.task = task;
        }
    }
}
