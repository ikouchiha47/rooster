package com.personalos.app.ui.settings

import com.personalos.mlsdk.ArtifactStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsModelTest {
    @Test
    fun `every status has a plain word`() {
        assertEquals("installed", modelStatusLabel(ArtifactStatus.READY))
        assertEquals("not installed", modelStatusLabel(ArtifactStatus.ABSENT))
        assertEquals("incomplete", modelStatusLabel(ArtifactStatus.PARTIAL))
        assertEquals("outdated", modelStatusLabel(ArtifactStatus.STALE))
    }

    @Test
    fun `size reads as whole megabytes`() {
        assertEquals("170 MB", modelSizeLabel(179_131_736L))
        assertEquals("31 MB", modelSizeLabel(33_385_008L))
    }

    @Test
    fun `only an installed model needs no download`() {
        assertFalse(needsDownload(ArtifactStatus.READY))
        assertTrue(needsDownload(ArtifactStatus.ABSENT))
        assertTrue(needsDownload(ArtifactStatus.PARTIAL))
        assertTrue(needsDownload(ArtifactStatus.STALE))
    }
}
