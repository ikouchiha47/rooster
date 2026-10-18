package com.personalos.app.data.sms

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One broadcast: the id tagged onto the messages, its people, and the untagged body. */
data class GroupRecord(
    val id: String,
    val participants: List<String>,
    val body: String,
    val sentAt: Long,
    val replies: List<GroupReply> = emptyList(),
)

/** One message on a broadcast: a member's tagged reply, or our own send. */
data class GroupReply(
    val from: String,
    val body: String,
    val at: Long,
)

/**
 * The opt-out **group id** on multi-recipient SMS.
 *
 * SMS has no thread field, so the id rides in the body as a short ASCII tail
 * (`\n\n[g:ab12cd]`) that any SMS app shows harmlessly and our ingest can parse.
 * Membership lives here (prefs JSON, no schema change), so a tagged reply can be
 * resolved back to its people.
 */
class GroupStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("sms_groups", Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** Six hex chars: short enough to keep the tail tiny, unique enough per send. */
    fun newId(): String =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(6)

    fun record(
        id: String,
        participants: List<String>,
        body: String,
        sentAt: Long,
    ) {
        write(
            GroupRecord(
                id = id,
                participants = clean(participants),
                body = body,
                sentAt = sentAt,
                replies = get(id)?.replies.orEmpty(),
            ),
        )
    }

    /**
     * The group for these people: an exact member match, else the largest existing
     * group whose members are all present — so adding someone grows the group
     * rather than starting a second one.
     */
    fun forMembers(people: List<String>): GroupRecord? = GroupIdentity.bestMatch(people, all())

    /**
     * A stable id from the member set: the same people always produce the same
     * group, so a fresh send rejoins its thread instead of making a second one.
     * The id is the group's identity and never changes once made.
     */
    fun idFor(people: List<String>): String = GroupIdentity.idFor(people)

    /** Grows a group's membership; the id stays, so the thread is not broken. */
    fun addMembers(
        id: String,
        people: List<String>,
    ) {
        val record = get(id) ?: return
        val merged = clean(record.participants + people)
        if (merged.size != record.participants.size) write(record.copy(participants = merged))
    }

    private fun clean(people: List<String>): List<String> = people.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun write(record: GroupRecord) {
        val json =
            JSONObject()
                .put("body", record.body)
                .put("sentAt", record.sentAt)
                .put("participants", JSONArray(record.participants))
        if (record.replies.isNotEmpty()) {
            val replies = JSONArray()
            record.replies.forEach { reply ->
                replies.put(
                    JSONObject()
                        .put("from", reply.from)
                        .put("body", reply.body)
                        .put("at", reply.at),
                )
            }
            json.put("replies", replies)
        }
        prefs.edit().putString(KEY_PREFIX + record.id, json.toString()).apply()
    }

    fun get(id: String): GroupRecord? =
        prefs.getString(KEY_PREFIX + id, null)?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                val people = json.getJSONArray("participants")
                GroupRecord(
                    id = id,
                    participants = List(people.length()) { people.getString(it) },
                    body = json.getString("body"),
                    sentAt = json.getLong("sentAt"),
                    replies = repliesOf(json),
                )
            }.getOrNull()
        }

    /** Adds a tagged reply to a known group; an unknown id is ignored. */
    fun attach(
        groupId: String,
        ulid: String,
        from: String,
        body: String,
        at: Long,
    ) {
        val raw = prefs.getString(KEY_PREFIX + groupId, null) ?: return
        runCatching {
            val json = JSONObject(raw)
            val replies = json.optJSONArray("replies") ?: JSONArray()
            replies.put(
                JSONObject()
                    .put("ulid", ulid)
                    .put("from", from)
                    .put("body", body)
                    .put("at", at),
            )
            json.put("replies", replies)
            prefs.edit().putString(KEY_PREFIX + groupId, json.toString()).apply()
        }
    }

    /** Newest last, so a thread renders in the order it happened. */
    fun replies(id: String): List<GroupReply> = get(id)?.replies.orEmpty()

    private fun repliesOf(json: JSONObject): List<GroupReply> {
        val raw = json.optJSONArray("replies") ?: return emptyList()
        return List(raw.length()) { index ->
            val reply = raw.getJSONObject(index)
            GroupReply(
                from = reply.getString("from"),
                body = reply.getString("body"),
                at = reply.getLong("at"),
            )
        }.sortedBy { it.at }
    }

    fun all(): List<GroupRecord> =
        prefs.all
            .filterKeys { it.startsWith(KEY_PREFIX) }
            .keys
            .mapNotNull { get(it.removePrefix(KEY_PREFIX)) }
            .sortedByDescending { it.sentAt }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_PREFIX = "g:"
    }
}

/** The tail appended to a tagged body. Kept ASCII and short for carrier safety. */
fun taggedBody(
    body: String,
    groupId: String,
): String = "$body\n\n[g:$groupId]"

/** The `[g:xxxxxx]` tail: strip it for display, return it for grouping. */
object GroupTag {
    private val TAIL = Regex("\\s*\\[g:([0-9a-f]{6})]\\s*$")

    fun strip(body: String): Pair<String, String?> {
        val match = TAIL.find(body) ?: return body to null
        return body.substring(0, match.range.first).trimEnd() to match.groupValues[1]
    }
}

/**
 * The pure half of group identity — no prefs, no Android — so the "same people,
 * same group" rule is testable on its own.
 */
object GroupIdentity {
    /** Trimmed, de-blanked, de-duplicated, order-preserved. */
    fun clean(people: List<String>): List<String> = people.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * A stable six-hex id from the member set. Order and repeats cannot change it,
     * so the same people always resolve to one group.
     */
    fun idFor(people: List<String>): String {
        val canonical = clean(people).sorted().joinToString(",")
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-1")
                .digest(canonical.toByteArray())
        return digest.take(3).joinToString("") { "%02x".format(it) }
    }

    /**
     * Which group a send to these people joins: an exact member match wins, else
     * the largest group whose members are all present. Null means a new group.
     */
    fun bestMatch(
        people: List<String>,
        groups: List<GroupRecord>,
    ): GroupRecord? {
        val wanted = clean(people).toSet()
        if (wanted.isEmpty()) return null
        return groups.firstOrNull { it.participants.toSet() == wanted }
            ?: groups
                .filter { it.participants.isNotEmpty() && wanted.containsAll(it.participants) }
                .maxByOrNull { it.participants.size }
    }
}
