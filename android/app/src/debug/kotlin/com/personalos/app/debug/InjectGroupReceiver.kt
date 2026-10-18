package com.personalos.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.personalos.app.data.sms.GroupStore
import java.util.UUID

/**
 * Debug-only group simulator. SMS has no thread id, so a group is only ever
 * "your broadcast plus whatever came back tagged with its id" — and you cannot
 * reply from your own second SIM to test that. This writes the group record
 * directly, exactly as ingest would, so the group UI can be exercised without a
 * real fan-out or a second phone.
 *
 * ```
 * adb shell am broadcast -a com.personalos.app.INJECT_GROUP \
 *     -n com.personalos.app/.debug.InjectGroupReceiver \
 *     --es group demo01 --es to "9836870428,9434853991" \
 *     --es body "dinner at 8?" --es from 9836870428 --es reply "works for me"
 * ```
 *
 * `group` names the id (6 hex in production, any string here). `to` + `body`
 * create the broadcast if it does not exist yet; `from` + `reply` append one
 * tagged reply. Repeat the reply-only form to stack more.
 */
class InjectGroupReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION) return
        val id = intent.getStringExtra(EXTRA_GROUP)
        if (id.isNullOrBlank()) {
            Log.w(TAG, "no group id given")
            return
        }
        val store = GroupStore(context.applicationContext)
        val to =
            intent
                .getStringExtra(EXTRA_TO)
                .orEmpty()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        if (to.isNotEmpty() && body.isNotEmpty() && store.get(id) == null) {
            store.record(id, to, body, System.currentTimeMillis())
        }
        val from = intent.getStringExtra(EXTRA_FROM)
        val reply = intent.getStringExtra(EXTRA_REPLY)
        if (!from.isNullOrBlank() && !reply.isNullOrBlank()) {
            store.attach(id, UUID.randomUUID().toString(), from, reply, System.currentTimeMillis())
        }
        Log.i(TAG, "injected group=$id people=${store.get(id)?.participants?.size} replies=${store.get(id)?.replies?.size}")
    }

    companion object {
        const val TAG = "InjectGroup"
        const val ACTION = "com.personalos.app.INJECT_GROUP"
        const val EXTRA_GROUP = "group"
        const val EXTRA_TO = "to"
        const val EXTRA_BODY = "body"
        const val EXTRA_FROM = "from"
        const val EXTRA_REPLY = "reply"
    }
}
