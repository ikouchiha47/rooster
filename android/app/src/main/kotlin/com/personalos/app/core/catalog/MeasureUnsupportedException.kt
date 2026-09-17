package com.personalos.app.core.catalog

/**
 * A reserved or unimplemented measure op was requested (ADR 0005 REQ-OP-06).
 * Loud, never a silent `false`: "not implemented" must not read as "no match".
 */
class MeasureUnsupportedException(
    message: String,
) : UnsupportedOperationException(message)
