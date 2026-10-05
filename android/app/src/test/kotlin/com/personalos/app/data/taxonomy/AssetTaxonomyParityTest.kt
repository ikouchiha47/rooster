package com.personalos.app.data.taxonomy

import com.personalos.app.core.tag.BundledVocabulary
import com.personalos.app.core.tag.TagDefinition
import com.personalos.app.core.tag.taxonomy.TaxonomyJson
import com.personalos.app.core.tag.taxonomy.source
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The asset is now the vocabulary the app loads, so it must not drift from the
 * code constant it replaced — otherwise "behaviour unchanged" is a claim, not a
 * fact. This fails the moment the two disagree on a tag, a term or a description.
 */
class AssetTaxonomyParityTest {
    private fun assetDefinitions(): List<TagDefinition> {
        val file = File("src/main/assets/taxonomy/taxonomy.v1.json")
        return TaxonomyJson.parse(file.readText()).source().definitions()
    }

    @Test
    fun `the bundled asset matches the code vocabulary`() {
        val asset = assetDefinitions().associateBy { it.tag }
        val code = BundledVocabulary.definitions().associateBy { it.tag }

        assertEquals("same tags", code.keys, asset.keys)
        for ((tag, expected) in code) {
            assertEquals("terms for $tag", expected.terms, asset.getValue(tag).terms)
            assertEquals("description for $tag", expected.description, asset.getValue(tag).description)
        }
    }
}
