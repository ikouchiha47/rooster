package com.personalos.app.core.catalog

import com.personalos.app.core.rules.FieldValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpMatrixTest {
    private fun facet(
        valueType: ValueType,
        measure: MeasureKind,
        ops: Set<String> = emptySet(),
    ) = Facet(id = "f", valueType = valueType, measure = measure, ops = ops.ifEmpty { OpMatrix.defaults(valueType, measure) })

    @Test
    fun `num counter allows item numeric ops only`() {
        val f = facet(ValueType.NUM, MeasureKind.COUNTER)
        for (op in listOf("eq", "ne", "lt", "lte", "gt", "gte")) assertTrue(op, OpMatrix.allowed(f, op))
        for (op in listOf("crossing", "delta", "contains")) assertFalse(op, OpMatrix.allowed(f, op))
    }

    @Test
    fun `num gauge allows item ops plus series`() {
        val f = facet(ValueType.NUM, MeasureKind.GAUGE)
        for (op in listOf("eq", "ne", "lt", "lte", "gt", "gte", "crossing", "delta", "min", "max")) {
            assertTrue(op, OpMatrix.allowed(f, op))
        }
    }

    @Test
    fun `str facet rejects gt at write`() {
        val f = facet(ValueType.STR, MeasureKind.COUNTER)
        assertThrows(IllegalArgumentException::class.java) {
            OpMatrix.require(f, "gt", FieldValue.Num(1.0))
        }
    }

    @Test
    fun `enum allows only eq and in`() {
        val f = facet(ValueType.ENUM, MeasureKind.COUNTER)
        assertTrue(OpMatrix.allowed(f, "eq"))
        assertTrue(OpMatrix.allowed(f, "in"))
        assertFalse(OpMatrix.allowed(f, "gt"))
        assertFalse(OpMatrix.allowed(f, "contains"))
    }

    @Test
    fun `value kind mismatch throws`() {
        val f = facet(ValueType.NUM, MeasureKind.COUNTER)
        assertThrows(IllegalArgumentException::class.java) {
            OpMatrix.require(f, "gt", FieldValue.Str("x"))
        }
    }

    @Test
    fun `reserved ops throw MeasureUnsupportedException`() {
        val f = facet(ValueType.NUM, MeasureKind.COUNTER)
        for (op in listOf("p50", "p95", "count")) {
            val error =
                assertThrows(MeasureUnsupportedException::class.java) {
                    OpMatrix.require(f, op, FieldValue.Num(1.0))
                }
            assertTrue(error.message!!.contains(op))
        }
    }
}
