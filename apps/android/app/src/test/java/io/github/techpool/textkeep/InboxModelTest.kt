package io.github.techpool.textkeep

import org.junit.Assert.*
import org.junit.Test

class InboxModelTest {
    private fun message(id: Long, thread: Long, body: String = "Synthetic message", read: Boolean = true, type: Int = 1) =
        LocalMessage(id, "Sender $thread", body, id * 1000, type, read, thread, -1)

    @Test fun searchAndCodeFiltersPreserveConversationActivityAndUnreadCounts() {
        val oldCode = message(1, 10, "Your OTP is 515253", read = false)
        val newest = message(3, 10, "A later reply", type = 2)
        val rows = listOf(oldCode, newest, message(2, 20))
        val thread = organizeInbox(rows, "515253", InboxFilter.CODES, emptySet(), emptySet()).single()
        assertEquals(newest, thread.latest)
        assertEquals(oldCode, thread.preview)
        assertEquals(1, thread.unread)
        assertEquals(thread.key, organizeInbox(rows, "", InboxFilter.UNREAD, emptySet(), emptySet()).single().key)
    }

    @Test fun pinnedThreadsSortFirstAndArchivesAreRestorableWithoutDeletingMessages() {
        val rows = listOf(message(1,10),message(2,20),message(3,30))
        assertEquals(listOf("thread:10","thread:30"),organizeInbox(rows,"",InboxFilter.ALL,setOf("thread:10"),setOf("thread:20")).map{it.key})
        assertEquals("thread:20",organizeInbox(rows,"",InboxFilter.ARCHIVED,emptySet(),setOf("thread:20")).single().key)
        assertEquals(3,organizeInbox(rows,"",InboxFilter.ALL,emptySet(),emptySet()).size)
    }

    @Test fun unknownProviderThreadsDoNotMergeDifferentSendersOrCountOutgoingAsUnread() {
        val a=message(1,0,read=false,type=2).copy(address="A")
        val b=message(2,0).copy(address="B")
        assertEquals(2,organizeInbox(listOf(a,b),"",InboxFilter.ALL,emptySet(),emptySet()).size)
        assertTrue(organizeInbox(listOf(a,b),"",InboxFilter.UNREAD,emptySet(),emptySet()).isEmpty())
    }

    @Test fun recentResumeDoesNotNeedReconciliationButInterruptedImportsAndStaleLaunchesDo() {
        val now=2_000_000L
        assertFalse(SyncScheduler.reconcileDue(now-1_000,0,now))
        assertTrue(SyncScheduler.reconcileDue(now-900_000,0,now))
        assertTrue(SyncScheduler.reconcileDue(now-1_000,2000,now))
        assertTrue(SyncScheduler.reconcileDue(null,0,now))
        assertTrue(SyncScheduler.reconcileDue(now+1_000,0,now))
    }
}
