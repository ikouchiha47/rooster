package com.personalos.app.ui.settings

import com.personalos.mlsdk.ModelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsModelTest {
    @Test
    fun `every status has a plain word`() {
        assertEquals("installed", modelStatusLabel(ModelStatus.READY))
        assertEquals("not installed", modelStatusLabel(ModelStatus.ABSENT))
        assertEquals("incomplete", modelStatusLabel(ModelStatus.PARTIAL))
        assertEquals("outdated", modelStatusLabel(ModelStatus.STALE))
    }

    @Test
    fun `size reads as whole megabytes`() {
        assertEquals("170 MB", modelSizeLabel(179_131_736L))
        assertEquals("31 MB", modelSizeLabel(33_385_008L))
    }

    @Test
    fun `only an installed model needs no download`() {
        assertFalse(needsDownload(ModelStatus.READY))
        assertTrue(needsDownload(ModelStatus.ABSENT))
        assertTrue(needsDownload(ModelStatus.PARTIAL))
        assertTrue(needsDownload(ModelStatus.STALE))
    }
}
