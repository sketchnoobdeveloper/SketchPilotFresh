package snd.sketchpilot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import snd.sketchpilot.core.sketchware.SectionedCodec
import snd.sketchpilot.core.sketchware.SketchwareCrypto
import java.nio.file.Files

class CryptoTest {
    @Test
    fun roundTrip() {
        val plain = "@main.xml\n{\"id\":\"a\"}\n"
        val encrypted = SketchwareCrypto.encrypt(plain)
        assertTrue(SketchwareCrypto.looksEncrypted(encrypted))
        assertEquals(plain, SketchwareCrypto.decrypt(encrypted))
    }

    @Test
    fun sectionRoundTrip() {
        val text = "@one\na\nb\n@two\nc\n"
        val parsed = SectionedCodec.parse(text)
        assertEquals(2, parsed.size)
        assertEquals("one", parsed[0].name)
        assertEquals(listOf("a", "b"), parsed[0].lines)
        assertEquals(text, SectionedCodec.serialize(parsed))
    }

    @Test
    fun tempDirWorks() {
        assertTrue(Files.createTempDirectory("sp-crypto").toFile().isDirectory)
    }
}
