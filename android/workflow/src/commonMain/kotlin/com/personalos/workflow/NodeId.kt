package com.personalos.workflow

/** A node's identity within one workflow. Cheap and comparable, so it can key maps. */
@JvmInline
value class NodeId(
    val value: String,
) {
    override fun toString(): String = value
}
