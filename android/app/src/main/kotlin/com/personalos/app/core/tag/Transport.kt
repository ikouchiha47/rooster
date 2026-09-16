package com.personalos.app.core.tag

/**
 * The channel text arrived over — SMS/RSS/JSON/WEB. This is transport, not a
 * source identity: it only drives the tagger's priors (see [TagInput.source]).
 */
enum class Transport { SMS, RSS, JSON, WEB }
