// SPDX-License-Identifier: GPL-3.0-or-later
package com.voiceskip.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes real UTF-8 text or an Office Open XML document, without a server. */
object TranscriptExporter {
    enum class Format(val extension: String, val mimeType: String) {
        TXT("txt", "text/plain"),
        DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    }

    fun write(text: String, format: Format, output: OutputStream) {
        when (format) {
            Format.TXT -> output.write(text.toByteArray(Charsets.UTF_8))
            Format.DOCX -> writeDocx(text, output)
        }
    }

    private fun writeDocx(text: String, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, xml: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>""".trimIndent())
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>""".trimIndent())
            zip.putNextEntry(ZipEntry("word/document.xml"))
            fun xml(value: String) = zip.write(value.toByteArray(Charsets.UTF_8))
            xml("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
            text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
                xml("""<w:p><w:pPr><w:spacing w:after="0" w:line="276" w:lineRule="auto"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri"/><w:sz w:val="22"/></w:rPr>""")
                line.split('\t').forEachIndexed { index, part ->
                    if (index > 0) xml("<w:tab/>")
                    xml("""<w:t xml:space="preserve">${escapeXml(part)}</w:t>""")
                }
                xml("</w:r></w:p>")
            }
            xml("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>""")
            zip.closeEntry()
        }
    }

    private fun escapeXml(text: String): String = buildString {
        text.codePoints().forEach { code ->
            when (code) {
                38 -> append("&amp;")
                60 -> append("&lt;")
                62 -> append("&gt;")
                else -> if (code == 9 || code == 10 || code == 13 ||
                    code in 0x20..0xD7FF || code in 0xE000..0xFFFD || code in 0x10000..0x10FFFF
                ) appendCodePoint(code)
            }
        }
    }
}
