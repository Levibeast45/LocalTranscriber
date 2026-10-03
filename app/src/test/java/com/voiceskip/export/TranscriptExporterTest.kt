// SPDX-License-Identifier: GPL-3.0-or-later
package com.voiceskip.export

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class TranscriptExporterTest {
    private val sample = "Bonjour, été & café <test>.\n\nالعربية 中文 😀\nFin."

    private fun export(text: String, format: TranscriptExporter.Format): ByteArray =
        ByteArrayOutputStream().also { TranscriptExporter.write(text, format, it) }.toByteArray()

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
            }
        }
        return result
    }

    private fun parse(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    @Test fun `TXT preserves all multilingual text and paragraph breaks in UTF-8`() {
        assertEquals(sample, export(sample, TranscriptExporter.Format.TXT).toString(Charsets.UTF_8))
    }

    @Test fun `DOCX is a valid package with faithful text and paragraphs`() {
        val bytes = export(sample, TranscriptExporter.Format.DOCX)
        val files = entries(bytes)
        files.values.forEach { parse(it) }
        val types = parse(files.getValue("[Content_Types].xml"))
        val mainPart = types.getElementsByTagNameNS(
            "http://schemas.openxmlformats.org/package/2006/content-types", "Override"
        ).item(0).attributes
        assertEquals("/word/document.xml", mainPart.getNamedItem("PartName").nodeValue)
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
            mainPart.getNamedItem("ContentType").nodeValue)
        val rel = parse(files.getValue("_rels/.rels")).getElementsByTagNameNS(
            "http://schemas.openxmlformats.org/package/2006/relationships", "Relationship"
        ).item(0).attributes
        assertEquals("word/document.xml", rel.getNamedItem("Target").nodeValue)
        val doc = parse(files.getValue("word/document.xml"))
        val paragraphs = doc.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "p")
        val actual = (0 until paragraphs.length).joinToString("\n") { paragraphs.item(it).textContent }
        assertEquals(sample, actual)
        // Available for independent interoperability checks after running the test.
        File("build/export-test/transcription.docx").apply { parentFile.mkdirs(); writeBytes(bytes) }
    }

    @Test fun `DOCX normalizes CRLF and filters illegal XML controls while retaining emoji`() {
        val files = entries(export("A\r\nB\u0000😀", TranscriptExporter.Format.DOCX))
        val doc = parse(files.getValue("word/document.xml"))
        val paragraphs = doc.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "p")
        assertEquals(2, paragraphs.length)
        assertEquals("B😀", paragraphs.item(1).textContent)
    }

    @Test fun `long transcripts are not truncated`() {
        val text = (1..2000).joinToString("\n") { "Phrase $it : café & liberté." }
        val doc = parse(entries(export(text, TranscriptExporter.Format.DOCX)).getValue("word/document.xml"))
        val paragraphs = doc.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "p")
        assertEquals(2000, paragraphs.length)
        assertEquals("Phrase 2000 : café & liberté.", paragraphs.item(1999).textContent)
    }

    @Test fun `write failures propagate so UI cannot report a false success`() {
        TranscriptExporter.Format.values().forEach { format ->
            val failure = runCatching {
                TranscriptExporter.write(sample, format, object : OutputStream() {
                    override fun write(value: Int) { throw IOException("Full storage") }
                })
            }.exceptionOrNull()
            assertTrue(failure is IOException)
        }
    }
}
