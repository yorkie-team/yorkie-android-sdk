package dev.yorkie.api

import dev.yorkie.api.v1.changePack
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.ChangePack.Companion.PRESENCE_PATCH_CAPABILITY
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.time.VersionVector
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Covers `ChangePack.capabilities` (field 9) decode + [ChangePack.hasCapability],
 * added in JS `b50f8900` (#1477). The server sets the field on every pack it
 * returns; this SDK reads it but never writes it back (D5).
 */
class ChangeConverterTest {

    private fun domainChangePack(capabilities: List<String> = emptyList()) = ChangePack(
        documentKey = "key",
        checkPoint = CheckPoint.InitialCheckPoint,
        changes = emptyList(),
        snapshot = null,
        isRemoved = false,
        versionVector = VersionVector.INITIAL_VERSION_VECTOR,
        capabilities = capabilities,
    )

    @Test
    fun `a pack with capabilities decodes them from bytes`() {
        val pb = changePack {
            documentKey = "key"
            capabilities.addAll(listOf(PRESENCE_PATCH_CAPABILITY))
        }

        val reparsed = PBChangePack.parseFrom(pb.toByteArray())
        val decoded = reparsed.toChangePack()

        assertEquals(listOf(PRESENCE_PATCH_CAPABILITY), decoded.capabilities)
        assertTrue(decoded.hasCapability(PRESENCE_PATCH_CAPABILITY))
    }

    @Test
    fun `a pack without the capabilities field decodes to an empty list`() {
        val pb = changePack {
            documentKey = "key"
        }

        val reparsed = PBChangePack.parseFrom(pb.toByteArray())
        val decoded = reparsed.toChangePack()

        assertEquals(emptyList<String>(), decoded.capabilities)
        assertFalse(decoded.hasCapability(PRESENCE_PATCH_CAPABILITY))
    }

    @Test
    fun `encoding a ChangePack never writes capabilities`() {
        val pack = domainChangePack(capabilities = listOf(PRESENCE_PATCH_CAPABILITY))

        val pb = pack.toPBChangePack()
        val reparsed = PBChangePack.parseFrom(pb.toByteArray())

        assertTrue(reparsed.capabilitiesList.isEmpty())
    }

    @Test
    fun `capabilities round trip through serialized bytes when present`() {
        val pb = changePack {
            documentKey = "key"
            capabilities.addAll(listOf(PRESENCE_PATCH_CAPABILITY, "restore-element"))
        }

        val decoded = PBChangePack.parseFrom(pb.toByteArray()).toChangePack()

        assertEquals(listOf(PRESENCE_PATCH_CAPABILITY, "restore-element"), decoded.capabilities)
    }
}
