package com.personalos.app.data.work

import androidx.work.NetworkType
import com.personalos.app.data.SmsIngest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The worker's two obligations: call the same ingest entry point the observer
 * calls, and be scheduled at WorkManager's floor without a network constraint
 * (local provider + Room work must not wait for connectivity).
 */
class SmsSyncWorkerTest {
    private class RecordingSmsIngest : SmsIngest {
        var calls = 0
            private set

        override suspend fun ingestNewMessages(): Int {
            calls++
            return 5
        }
    }

    @Test
    fun `the worker delegates to the shared SmsIngest entry point`() {
        val ingest = RecordingSmsIngest()

        val added = runBlocking { SmsSyncWorker.ingest(ingest) }

        assertEquals(1, ingest.calls)
        assertEquals(5, added)
    }

    @Test
    fun `the sms poll asks for the 15-minute floor`() {
        val request = SyncScheduler.smsSyncRequest()

        assertEquals(
            TimeUnit.MINUTES.toMillis(SyncScheduler.MIN_INTERVAL_MINUTES),
            request.workSpec.intervalDuration,
        )
    }

    @Test
    fun `the sms poll has no network constraint`() {
        val request = SyncScheduler.smsSyncRequest()

        assertEquals(NetworkType.NOT_REQUIRED, request.workSpec.constraints.requiredNetworkType)
    }
}
