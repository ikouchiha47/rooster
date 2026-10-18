package com.personalos.app.core

/**
 * How a **sender** reads in the Messages list — a *relationship*, not a subject.
 *
 * This is deliberately separate from the message's category (what a message
 * *is*, [com.personalos.app.core.category.Categorizer]): a bank is
 * `transactional` and never Social or Others; a friend's 200-message thread is
 * Social whatever they talk about. Only individual numbers (a person) land here;
 * business senders (DLT headers, short codes) are classified by content instead.
 *
 * The inputs are two facts already stored — the conversation's `msg_count` and
 * whether the number is a saved contact — so the split costs no query and no
 * model, and it re-buckets on its own as the count changes.
 */
enum class SenderBucket {
    /** A known person: a saved contact, or a thread you actually have. */
    SOCIAL,

    /** An unsaved number with some history, not yet a relationship. */
    PEOPLE,

    /** An unsaved number you have barely heard from — a one-off. */
    OTHERS,
}

/** At or above this, an unsaved number is treated as a relationship. */
const val SOCIAL_MIN = 20

/** At or below this, an unsaved number is a one-off. */
const val OTHERS_MAX = 5

/**
 * The one decision, as a **pure function** so the edges are a test and the
 * thresholds are arguments (a caller may tune them without a new rule):
 *
 *  - **Social** — a saved contact (however few messages) *or* a real thread.
 *  - **Others** — unsaved and barely any history.
 *  - **People** — the space between: unsaved, but more than a one-off.
 */
fun senderBucket(
    msgCount: Int,
    savedContact: Boolean,
    socialMin: Int = SOCIAL_MIN,
    othersMax: Int = OTHERS_MAX,
): SenderBucket =
    when {
        savedContact || msgCount >= socialMin -> SenderBucket.SOCIAL
        msgCount > othersMax -> SenderBucket.PEOPLE
        else -> SenderBucket.OTHERS
    }
