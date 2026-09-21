package com.personalos.app.data

/**
 * Removes the app's stored copy of an item.
 *
 * ### What this deletes, and what it does not
 *
 * Only rows the app holds: the event and its tags, mentions, typed fields and
 * rule matches. **The message on the device is untouched** — nothing here reads
 * or writes `content://sms`. Removing the device copy too is a separate
 * capability (WhatsApp's "delete for everyone"), not built; if it is ever
 * added, it belongs behind a permission-and-confirmation path of its own, not
 * behind a swipe.
 *
 * ### Two consequences worth knowing
 *
 * - **It is effectively permanent for that message.** `SmsSource` keeps a
 *   high-water mark of the highest message id it has ingested, so a deleted
 *   message is not re-read. (The one exception is
 *   [SmsSource.resetMarkIfTableIsEmpty]: if the *whole* events table is ever
 *   empty, the mark resets and the inbox is re-ingested.)
 * - **Dependents go first.** Tags, mentions, fields and matches are deleted by
 *   `item_id` before the event row, the same order the schema migrations use,
 *   so a partial failure leaves no orphans pointing at a row that is gone.
 */
class MessageDeleteRepository(
    private val dao: MessageDeleteDao,
) {
    /**
     * Deletes the stored item and its sidecars. Returns true when an event row
     * was actually removed — false means there was nothing there, which is a
     * no-op rather than an error.
     *
     * The sidecar deletes run unconditionally: they are idempotent, and skipping
     * them for an item whose event row had already gone would be the one way to
     * leave orphans behind.
     */
    suspend fun remove(ulid: String): Boolean {
        dao.deleteTags(ulid)
        dao.deleteMentions(ulid)
        dao.deleteFields(ulid)
        dao.deleteMatches(ulid)
        return dao.deleteEvent(ulid) > 0
    }
}
