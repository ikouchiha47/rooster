package com.personalos.app.ui.common

import com.personalos.app.ui.theme.CategoryColors
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Row spine colours: the edge repeats the leading subject chip's fill (cream
 * when there is no subject), so the list scans as one layer.
 */
class TagLineSpineTest {
    @Test
    fun `subjects map to their chip fills`() {
        assertEquals(CategoryColors.Teal, subjectSpineColor(listOf("finance")))
        assertEquals(CategoryColors.Plum, subjectSpineColor(listOf("tech")))
        assertEquals(CategoryColors.Mustard, subjectSpineColor(listOf("travel")))
        assertEquals(CategoryColors.Chartreuse, subjectSpineColor(listOf("weather")))
        assertEquals(CategoryColors.Indigo, subjectSpineColor(listOf("paper")))
    }

    @Test
    fun `games takes periwinkle`() {
        assertEquals(CategoryColors.Periwinkle, subjectSpineColor(listOf("games")))
    }

    @Test
    fun `lookup is case-insensitive`() {
        assertEquals(CategoryColors.Teal, subjectSpineColor(listOf("Finance")))
        assertEquals(CategoryColors.Periwinkle, subjectSpineColor(listOf("GAMES")))
    }

    @Test
    fun `the leading subject wins`() {
        assertEquals(CategoryColors.Plum, subjectSpineColor(listOf("promo", "tech", "finance")))
        assertEquals(CategoryColors.Teal, subjectSpineColor(listOf("finance", "tech")))
    }

    @Test
    fun `no subject takes the cream fallback`() {
        assertEquals(RadarColors.paper4, subjectSpineColor(emptyList()))
        assertEquals(RadarColors.paper4, subjectSpineColor(listOf("promo")))
        assertEquals(RadarColors.paper4, subjectSpineColor(listOf("news")))
    }

    @Test
    fun `festival stays unmapped and takes cream`() {
        assertEquals(RadarColors.paper4, subjectSpineColor(listOf("festival")))
    }
}
