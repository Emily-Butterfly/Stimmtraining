package de.emilybutterfly.stimmtraining.data.document

import org.junit.Assert.assertEquals
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Baut Testdokumente so auf, wie Word, LibreOffice & Co. sie schreiben. */
object TestDocuments {
    const val W_NAMESPACES =
        """xmlns:wpc="http://schemas.microsoft.com/office/word/2010/wordprocessingCanvas" """ +
            """xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" """ +
            """xmlns:o="urn:schemas-microsoft-com:office:office" """ +
            """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
            """xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math" """ +
            """xmlns:v="urn:schemas-microsoft-com:vml" """ +
            """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" """ +
            """xmlns:w10="urn:schemas-microsoft-com:office:word" """ +
            """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" """ +
            """xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml" """ +
            """xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape" """ +
            """mc:Ignorable="w14""""

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>"""

    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""

    const val ODF_NAMESPACES =
        """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
            """xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
            """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
            """xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" """ +
            """xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" """ +
            """xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" """ +
            """xmlns:xlink="http://www.w3.org/1999/xlink" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
            """xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" """ +
            """office:version="1.3""""

    /** ZIP mit den Einträgen in dieser Reihenfolge; "mimetype" wird wie bei ODF unkomprimiert gespeichert. */
    fun zip(file: File, vararg entries: Pair<String, ByteArray>): File {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, data) in entries) {
                val entry = ZipEntry(name)
                if (name == "mimetype") {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.crc = CRC32().apply { update(data) }.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return file
    }

    fun docxXml(body: String, namespaces: String = W_NAMESPACES): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document $namespaces><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1417" w:right="1417" w:bottom="1134" w:left="1417" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>"""

    fun stylesXml(styles: String): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles $W_NAMESPACES><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:asciiTheme="minorHAnsi"/><w:sz w:val="22"/><w:lang w:val="de-DE"/></w:rPr></w:rPrDefault></w:docDefaults>$styles</w:styles>"""

    fun docx(file: File, body: String, styles: String? = null): File {
        val entries = mutableListOf(
            "[Content_Types].xml" to CONTENT_TYPES.toByteArray(),
            "_rels/.rels" to RELS.toByteArray(),
            "word/document.xml" to docxXml(body).toByteArray(),
        )
        if (styles != null) entries += "word/styles.xml" to stylesXml(styles).toByteArray()
        return zip(file, *entries.toTypedArray())
    }

    fun contentXml(body: String, automaticStyles: String = ""): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content $ODF_NAMESPACES><office:scripts/><office:font-face-decls><style:font-face style:name="Liberation Serif" svg:font-family="'Liberation Serif'"/></office:font-face-decls><office:automatic-styles>$automaticStyles</office:automatic-styles><office:body><office:text><text:sequence-decls><text:sequence-decl text:display-outline-level="0" text:name="Illustration"/></text:sequence-decls>$body</office:text></office:body></office:document-content>"""

    fun odt(file: File, body: String, automaticStyles: String = "", styles: String? = null, mimetype: String = "application/vnd.oasis.opendocument.text"): File {
        val entries = mutableListOf(
            "mimetype" to mimetype.toByteArray(),
            "content.xml" to contentXml(body, automaticStyles).toByteArray(),
        )
        if (styles != null) {
            entries += "styles.xml" to """<?xml version="1.0" encoding="UTF-8"?>
<office:document-styles $ODF_NAMESPACES><office:styles>$styles</office:styles><office:automatic-styles><style:style style:name="T1" style:family="text"><style:text-properties fo:font-style="italic"/></style:style></office:automatic-styles></office:document-styles>""".toByteArray()
        }
        entries += "META-INF/manifest.xml" to """<?xml version="1.0" encoding="UTF-8"?>
<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.3"><manifest:file-entry manifest:full-path="/" manifest:media-type="$mimetype"/></manifest:manifest>""".toByteArray()
        return zip(file, *entries.toTypedArray())
    }

    /** Kurzform zum Vergleichen: (Art, Text, Zeichen, Ebene) je Absatz. */
    fun TextDocument.outline(): List<String> = blocks.map { block ->
        buildString {
            append(block.style.name)
            if (block.marker != null) append(" ").append(block.marker)
            if (block.level > 0) append(" @").append(block.level)
            append(": ").append(block.text)
        }
    }

    /** Fett/kursiv gesetzte Abschnitte eines Absatzes, z. B. "**fett**", "*kursiv*", "***beides***". */
    fun TextBlock.markup(): String = spans.joinToString("") { span ->
        val mark = (if (span.bold) "**" else "") + (if (span.italic) "*" else "")
        mark + span.text + mark.reversed()
    }

    fun assertOutline(document: TextDocument, vararg expected: String) {
        assertEquals(expected.joinToString("\n"), document.outline().joinToString("\n"))
    }
}
