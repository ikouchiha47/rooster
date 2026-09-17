package com.personalos.app.core.catalog

/**
 * ADR 0005 T5: bundled descriptors as data — kinds, facets and their topics.
 * Add-only; a later release inserts rows, never rewrites stored specs.
 * Pure Kotlin so tests and the UI share one source without Android.
 */
object CatalogSeeds {
    fun facets(): List<Facet> =
        listOf(
            Facet(id = "subject", valueType = ValueType.ENUM, measure = MeasureKind.COUNTER, ops = setOf("eq", "in")),
            Facet(id = "nature", valueType = ValueType.ENUM, measure = MeasureKind.COUNTER, ops = setOf("eq", "in")),
            Facet(id = "marker", valueType = ValueType.FLAG, measure = MeasureKind.COUNTER, ops = setOf("eq")),
            Facet(id = "source", valueType = ValueType.ENUM, measure = MeasureKind.COUNTER, ops = setOf("eq", "in")),
            Facet(id = "title", valueType = ValueType.STR, measure = MeasureKind.COUNTER, ops = setOf("eq", "ne", "contains")),
            Facet(id = "content", valueType = ValueType.STR, measure = MeasureKind.COUNTER, ops = setOf("eq", "ne", "contains")),
            Facet(id = "place", valueType = ValueType.STR, measure = MeasureKind.COUNTER, ops = setOf("eq")),
            Facet(id = "party", valueType = ValueType.STR, measure = MeasureKind.COUNTER, ops = setOf("eq")),
            Facet(id = "amount", valueType = ValueType.NUM, measure = MeasureKind.COUNTER, ops = setOf("eq", "ne", "lt", "lte", "gt", "gte")),
            Facet(id = "sender", valueType = ValueType.STR, measure = MeasureKind.COUNTER, ops = setOf("eq", "ne", "contains")),
            Facet(
                id = "temp_c",
                valueType = ValueType.NUM,
                measure = MeasureKind.GAUGE,
                ops = setOf("eq", "ne", "lt", "lte", "gt", "gte", "crossing", "delta", "min", "max"),
            ),
            Facet(
                id = "rain_mm",
                valueType = ValueType.NUM,
                measure = MeasureKind.GAUGE,
                ops = setOf("eq", "ne", "lt", "lte", "gt", "gte", "crossing", "delta", "min", "max"),
            ),
            Facet(
                id = "rate",
                valueType = ValueType.NUM,
                measure = MeasureKind.GAUGE,
                ops = setOf("eq", "ne", "lt", "lte", "gt", "gte", "crossing", "delta", "min", "max"),
            ),
            Facet(
                id = "battery_pct",
                valueType = ValueType.NUM,
                measure = MeasureKind.GAUGE,
                ops = setOf("eq", "ne", "lt", "lte", "gt", "gte", "crossing", "delta", "min", "max"),
            ),
            Facet(id = "charging", valueType = ValueType.FLAG, measure = MeasureKind.GAUGE, ops = setOf("eq")),
            Facet(id = "network", valueType = ValueType.ENUM, measure = MeasureKind.COUNTER, ops = setOf("eq", "in")),
        )

    fun kinds(): List<Kind> =
        listOf(
            Kind(
                id = "sms",
                grain = IngestGrain.ITEM,
                topics = setOf("finance", "news"),
                facetIds = setOf("subject", "nature", "marker", "source", "amount", "sender", "title", "content", "place", "party"),
                enrichable = false,
            ),
            Kind(
                id = "rss",
                grain = IngestGrain.ITEM,
                topics = setOf("news", "weather"),
                facetIds = setOf("subject", "nature", "marker", "source", "title", "content", "place", "party"),
                enrichable = true,
            ),
            Kind(
                id = "search",
                grain = IngestGrain.ITEM,
                topics = setOf("news"),
                facetIds = setOf("subject", "nature", "marker", "source", "title", "content", "place", "party"),
                enrichable = true,
            ),
            Kind(
                id = "weather",
                grain = IngestGrain.SERIES,
                topics = setOf("weather"),
                facetIds = setOf("subject", "source", "temp_c", "rain_mm", "place"),
                enrichable = false,
            ),
            Kind(
                id = "fx",
                grain = IngestGrain.SERIES,
                topics = setOf("finance"),
                facetIds = setOf("subject", "source", "rate"),
                enrichable = false,
            ),
            Kind(
                id = "device",
                grain = IngestGrain.SERIES,
                topics = setOf("device"),
                facetIds = setOf("battery_pct", "charging", "network"),
                enrichable = false,
            ),
        )

    fun catalog(): Catalog = InMemoryCatalog(kinds = kinds(), facets = facets())
}
