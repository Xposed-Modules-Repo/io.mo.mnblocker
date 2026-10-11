package io.mo.mnblocker;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Exercise both real AOSP cancellation signatures using private fake service methods. */
public final class NotificationCleanupMethodsTest {
    @Test public void android11CancelsPostedRecordAndRefreshesLights() throws Exception {
        Legacy service = new Legacy();
        Record record = new Record();
        NotificationCleanupMethods methods = new NotificationCleanupMethods(Legacy.class, Record.class);
        methods.cancel(service, record, 8, 123);
        assertSame(record, service.removed);
        assertSame(record, service.canceled);
        assertTrue(service.wasPosted);
        assertFalse(service.sendDelete);
        assertEquals(8, service.reason);
        assertNull(service.listener);
        assertTrue(service.lightsUpdated);
    }

    @Test public void android15And16ReceiveElapsedRealtimeForPendingRecord() throws Exception {
        Modern service = new Modern();
        Record record = new Record();
        NotificationCleanupMethods methods = new NotificationCleanupMethods(Modern.class, Record.class);
        methods.cancel(service, record, 8, 456);
        assertSame(record, service.removed);
        assertSame(record, service.canceled);
        assertFalse(service.wasPosted);
        assertFalse(service.sendDelete);
        assertEquals(456, service.elapsed);
        assertEquals(8, service.reason);
        assertNull(service.listener);
    }

    @Test public void vendorSubclassCanInheritPrivateNativeMethods() throws Exception {
        Vendor service = new Vendor();
        Record record = new Record();
        new NotificationCleanupMethods(Vendor.class, Record.class).cancel(service, record, 8, 0);
        assertSame(record, service.canceled);
    }

    @Test public void unsupportedCancellationIsDetectedBeforeRemovingAnything() {
        try {
            new NotificationCleanupMethods(Unsupported.class, Record.class);
            fail("Unsupported service must not enable cleanup");
        } catch (NoSuchMethodException expected) {
            assertTrue(expected.getMessage().contains("cancelNotificationLocked"));
        }
    }

    private static class Record {}

    private static class Legacy {
        Record removed;
        Record canceled;
        boolean wasPosted;
        boolean sendDelete;
        int reason;
        String listener;
        boolean lightsUpdated;

        private boolean removeFromNotificationListsLocked(Record record) {
            removed = record;
            return true;
        }
        private void cancelNotificationLocked(Record record, boolean sendDelete, int reason,
                                              boolean wasPosted, String listener) {
            assertSame(removed, record);
            canceled = record;
            this.sendDelete = sendDelete;
            this.reason = reason;
            this.wasPosted = wasPosted;
            this.listener = listener;
        }
        private void updateLightsLocked() { lightsUpdated = true; }
    }

    private static final class Vendor extends Legacy {}

    private static final class Modern {
        Record removed;
        Record canceled;
        boolean wasPosted;
        boolean sendDelete;
        int reason;
        String listener;
        long elapsed;

        private boolean removeFromNotificationListsLocked(Record record) {
            removed = record;
            return false;
        }
        private void cancelNotificationLocked(Record record, boolean sendDelete, int reason,
                                              boolean wasPosted, String listener, long elapsed) {
            assertSame(removed, record);
            canceled = record;
            this.sendDelete = sendDelete;
            this.reason = reason;
            this.wasPosted = wasPosted;
            this.listener = listener;
            this.elapsed = elapsed;
        }
    }

    private static final class Unsupported {
        private boolean removeFromNotificationListsLocked(Record record) {
            fail("No state changes allowed on an unsupported ROM");
            return false;
        }
    }
}
