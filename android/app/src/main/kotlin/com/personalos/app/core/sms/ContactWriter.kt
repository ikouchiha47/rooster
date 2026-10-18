package com.personalos.app.core.sms

/** Writes a new contact to the device address book, so every app sees it. */
interface ContactWriter {
    /** True when the contact was created. False on a missing grant or a bad input. */
    suspend fun create(
        name: String,
        number: String,
        google: Boolean,
    ): Boolean
}
