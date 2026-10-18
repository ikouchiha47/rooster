package com.personalos.app.data.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import com.personalos.app.core.sms.CallInteraction
import com.personalos.app.core.sms.RecentCallers
import com.personalos.app.data.contacts.normaliseNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the call log into one row per number: the latest call time, whether any
 * call **connected** (`attended`), and whether any was **missed**.
 *
 * Permission-guarded; a missing grant is an empty list, not an error. Bounded to
 * [LIMIT] distinct numbers, newest first.
 */
class AndroidRecentCallers(
    private val context: Context,
) : RecentCallers {
    override suspend fun interactions(): List<CallInteraction> = withContext(Dispatchers.IO) { read() }

    private fun read(): List<CallInteraction> {
        if (!granted()) return emptyList()
        return runCatching {
            val byNumber = LinkedHashMap<String, CallInteraction>()
            context.contentResolver
                .query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.TYPE,
                        CallLog.Calls.DURATION,
                        CallLog.Calls.DATE,
                        CallLog.Calls.PHONE_ACCOUNT_ID,
                    ),
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC",
                )?.use { cursor ->
                    val numberCol = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                    val typeCol = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                    val durationCol = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                    val dateCol = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                    val accountCol = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
                    while (cursor.moveToNext() && byNumber.size < LIMIT) {
                        val number = normaliseNumber(cursor.getString(numberCol)) ?: continue
                        val type = cursor.getInt(typeCol)
                        val duration = cursor.getLong(durationCol)
                        val at = cursor.getLong(dateCol)
                        val accountId = if (accountCol >= 0) cursor.getString(accountCol) else null
                        val missed = type == CallLog.Calls.MISSED_TYPE
                        // Connected = came in or went out and actually lasted.
                        val attended =
                            !missed &&
                                duration > 0 &&
                                (type == CallLog.Calls.INCOMING_TYPE || type == CallLog.Calls.OUTGOING_TYPE)
                        val previous = byNumber[number]
                        byNumber[number] =
                            CallInteraction(
                                number = number,
                                lastAt = maxOf(previous?.lastAt ?: at, at),
                                attended = attended || previous?.attended == true,
                                missed = missed || previous?.missed == true,
                                accountId = previous?.accountId ?: accountId,
                            )
                    }
                }
            byNumber.values.sortedByDescending { it.lastAt }
        }.getOrDefault(emptyList())
    }

    private fun granted(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG,
        ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val LIMIT = 1_000
    }
}
