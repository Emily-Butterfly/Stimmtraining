package de.emilybutterfly.stimmtraining.data.document

import android.app.Application
import de.emilybutterfly.stimmtraining.data.document.TestDocuments.assertOutline
import de.emilybutterfly.stimmtraining.data.document.TestDocuments.markup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RtfReaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun read(rtf: String, maxChars: Int = TextDocumentBuilder.DEFAULT_MAX_CHARS): TextDocument {
        val file = folder.newFile().apply { writeBytes(rtf.toByteArray(Charsets.ISO_8859_1)) }
        return DocumentText.read(file, DocumentKind.Rtf, maxChars)
    }

    @Test
    fun wordPadDocument() {
        val document = read(
            """{\rtf1\ansi\ansicpg1252\deff0\nouicompat\deflang1031{\fonttbl{\f0\fnil\fcharset0 Calibri;}{\f1\fnil\fcharset2 Symbol;}}
{\colortbl ;\red255\green0\blue0;}
{\*\generator Riched20 10.0.19041}\viewkind4\uc1
\pard\sa200\sl276\slmult1\f0\fs22\lang7 Gr\'fc\'dfe aus K\'f6ln, \b fett\b0  und \i kursiv\i0 . Preis: 5 \u8364? \par
Zweite Zeile\line mit Umbruch\par
{\pntext\f1\'B7\tab}{\*\pn\pnlvlblt\pnf1\pnindent0{\pntxtb\'B7}}\fi-360\li720\sa200\sl276\slmult1 Erster Punkt\par
{\pntext\f1\'B7\tab}Zweiter Punkt\par
\pard\sa200\sl276\slmult1\par
}
""",
        )
        assertOutline(
            document,
            "Paragraph: Grüße aus Köln, fett und kursiv. Preis: 5 €",
            "Paragraph: Zweite Zeile\nmit Umbruch",
            "ListItem •: Erster Punkt",
            "ListItem •: Zweiter Punkt",
        )
        assertEquals("Grüße aus Köln, **fett** und *kursiv*. Preis: 5 €", document.blocks[0].markup())
    }

    @Test
    fun wordDocumentWithStylesListsFieldsAndSkippedParts() {
        val document = read(
            """{\rtf1\adeflang1025\ansi\ansicpg1252\uc1\adeff31507\deff0\stshfdbch31505\deflang1031\deflangfe1031{\fonttbl{\f0\fbidi \froman\fcharset0\fprq2{\*\panose 02020603050405020304}Times New Roman;}
{\f3\fbidi \froman\fcharset2\fprq2{\*\panose 05050102010706020507}Symbol;}{\f40\fbidi \fswiss\fcharset204\fprq2 Calibri Cyr;}}
{\colortbl;\red0\green0\blue0;\red0\green0\blue255;}
{\*\defchp \f31506\fs22\lang1031\langfe1033\langfenp1033 }{\*\defpap \ql \li0\ri0\sa160\sl259\slmult1\widctlpar\wrapdefault }
{\stylesheet{\ql \li0\ri0\sa160\sl259\slmult1\widctlpar\wrapdefault\rtlch\fcs1 \af31507\afs22\alang1025 \ltrch\fcs0 \f31506\fs22\lang1031 \snext0 \sqformat \spriority0 Normal;}
{\s1\ql \li0\ri0\sb240\sl259\slmult1\keepn\widctlpar\wrapdefault\outlinelevel0\rin0\lin0\itap0 \rtlch\fcs1 \af31503\afs32 \ltrch\fcs0 \fs32\cf19\lang1031 \sbasedon0 \snext0 \slink15 \sqformat \spriority9 heading 1;}
{\s2\ql \li0\ri0\sb40\keepn\widctlpar\outlinelevel1 \fs26 \sbasedon0 \snext0 \slink16 \sunhideused \sqformat \spriority9 heading 2;}
{\*\cs10 \additive \ssemihidden \sunhideused \spriority1 Default Paragraph Font;}
{\s17\ql \li720\ri0\sa160\contextualspace \sbasedon0 \snext17 \sqformat \spriority34 List Paragraph;}
{\s18\ql \li864\ri864\sb200\sa160\qc \i\cf20 \sbasedon0 \snext0 \slink19 \sqformat \spriority29 Quote;}}
{\*\listtable{\list\listtemplateid-1{\listlevel\levelnfc23\levelnfcn23{\leveltext\leveltemplateid67567617\'01\u-3913 ?;}{\levelnumbers;}\f3\fbias0 \fi-360\li720\lin720 }{\listname ;}\listid1}}
{\*\listoverridetable{\listoverride\listid1\listoverridecount0\ls1}}
{\*\rsidtbl \rsid2627141}{\mmathPr\mmathFont34\mbrkBin0}
{\info{\title Testdokument}{\author Emily}{\operator Emily}{\creatim\yr2026\mo1\dy1\hr10\min0}}
{\*\xmlnstbl {\xmlns1 http://schemas.microsoft.com/office/word/2003/wordml}}
\paperw11906\paperh16838\margl1417\margr1417 \ltrsect
{\header \ltrpar \pard\plain \ql Kopfzeile\par}{\footer \pard\plain Fu\'dfzeile\par}
\pard\plain \ltrpar\s1\ql \li0\ri0\sb240\keepn\outlinelevel0 \rtlch\fcs1 \ltrch\fcs0 \fs32 {\rtlch\fcs1 \ltrch\fcs0 \insrsid2627141 \hich\af31502\dbch\af31501\loch\f31502 \'dcberschrift}{\rtlch\fcs1 \ltrch\fcs0 \insrsid2627141 \par }
\pard\plain \ltrpar\s2\ql \fs26 {\insrsid1 Zweite Ebene}{\par }
\pard\plain \ltrpar\ql \li0\ri0\sa160 {\insrsid1 Text mit }{\b\insrsid1 fett}{\insrsid1  und }{\i\insrsid1 kursiv}{\insrsid1 , ein Link: }{\field{\*\fldinst {\insrsid1  HYPERLINK "https://example.org" }}{\fldrslt {\cs20\ul\cf2\insrsid1 Beispiel}}}{\insrsid1 .}{\*\bkmkstart Marke}{\*\bkmkend Marke}{\insrsid1 {\footnote \pard\plain Fu\'dfnote\par}\par }
{\listtext\pard\plain\ltrpar \rtlch\fcs1 \af0 \ltrch\fcs0 \f3\insrsid1 \loch\af3\dbch\af31501\hich\f3 \'b7\tab}\pard\plain \ltrpar\s17\ql \fi-360\li720\ri0\sa160\ls1\ilvl0 {\insrsid1 Punkt eins\par }
{\listtext\pard\plain\ltrpar \f3 \'b7\tab}\pard\plain \ltrpar\s17\ql \fi-360\li1440\ls1\ilvl1 {\insrsid1 Unterpunkt\par }
\pard\plain \ltrpar\s18\ql {\insrsid1 Zitat\par }
\pard\plain \ltrpar\ql {\f40\insrsid1 \'cf\'f0\'e8\'e2\'e5\'f2}{\insrsid1  }{\v\insrsid1 versteckt}{\deleted\insrsid1 weg}{\insrsid1 Ende\par }
{\*\themedata 504b030414000600080000002100e9de0fbfff0000001c020000130000005b436f6e74656e745f54797065735d2e786d6cac91cb4ec3301045f748fc83e52d4a}
{\*\datastore 0105000002000000}
{\pict{\*\picprop}\wmetafile8\picw100\pich100 010009000003}
}""",
        )
        assertOutline(
            document,
            "Heading1: Überschrift",
            "Heading2: Zweite Ebene",
            "Paragraph: Text mit fett und kursiv, ein Link: Beispiel.",
            "ListItem •: Punkt eins",
            "ListItem • @1: Unterpunkt",
            "Quote: Zitat",
            "Paragraph: Привет Ende",
        )
        assertEquals("Text mit **fett** und *kursiv*, ein Link: Beispiel.", document.blocks[2].markup())
    }

    @Test
    fun libreOfficeDocument() {
        val document = read(
            """{\rtf1\ansi\deff3\adeflang1025
{\fonttbl{\f0\froman\fprq2\fcharset0 Times New Roman;}{\f1\froman\fprq2\fcharset2 Symbol;}{\f3\froman\fprq2\fcharset0 Liberation Serif{\*\falt Times New Roman};}}
{\colortbl;\red0\green0\blue0;\red128\green128\blue128;}
{\stylesheet{\s0\snext0\dbch\af6\langfe2052\dbch\af7\afs24\alang1081\loch\f3\hich\af3\fs24\lang1031 Normal;}
{\s1\sbasedon17\snext18\ilvl0\outlinelevel0\ql\sb240\sa120\keepn\b\dbch\af6\afs36\ab\loch\f4\fs36 Heading 1;}
{\s18\sbasedon0\snext18\sl276\slmult1\ql\sb0\sa140 Text Body;}
}{\*\generator LibreOffice/24.2.1.2${'$'}Linux_X86_64 LibreOffice_project/}{\info{\creatim\yr2026\mo1\dy1\hr12\min0}{\revtim\yr0\mo0\dy0\hr0\min0}}{\*\userprops}\deftab709
\hyphauto1\viewscale100
{\*\pgdsctbl
{\pgdsc0\pgdscuse451\pgwsxn11906\pghsxn16838\marglsxn1134\margrsxn1134\pgdscnxt0 Default Page Style;}}
\formshade\paperh16838\paperw11906\margl1134\margr1134\margt1134\margb1134\sectd\sbknone\sftnnar\saftnnrlc\sectunlocked1\pgwsxn11906\pghsxn16838
{\*\ftnsep\chftnsep}\pgndec\pard\plain \s1\ilvl0\outlinelevel0\ql\sb240\sa120\keepn\b\dbch\af6\afs36\ab\loch\f4\fs36{\loch
Der Nordwind und die Sonne}
\par \pard\plain \s18\sl276\slmult1\ql\sb0\sa140\dbch\af6\langfe2052\dbch\af7\afs24\alang1081\loch\f3\hich\af3\fs24\lang1031{\loch
Einst stritten sich Nordwind und Sonne, wer von ihnen beiden wohl der St\u228\'e4rkere w\u228\'e4re.}
\par }""",
        )
        assertOutline(
            document,
            "Heading1: Der Nordwind und die Sonne",
            "Paragraph: Einst stritten sich Nordwind und Sonne, wer von ihnen beiden wohl der Stärkere wäre.",
        )
    }

    @Test
    fun unicodeWithFallbackCharacters() {
        val document = read(
            """{\rtf1\ansi\ansicpg1252\uc1 Euro \u8364?, Anf\u252\'fchrung: \ldblquote Hallo\rdblquote , {\uc2 Chinesisch \u20013\'d6\'d0\u25991\'ce\'c4} nach Gruppe ?, Emoji \u-10179?\u-8704?, ohne Ersatz {\uc0 \u8730}.\par}""",
        )
        assertEquals("Euro €, Anführung: “Hallo”, Chinesisch 中文 nach Gruppe ?, Emoji 😀, ohne Ersatz √.", document.plainText)
    }

    @Test
    fun specialSymbolsAndEscapes() {
        val document = read(
            """{\rtf1\ansi Gedanken\emdash strich, von 1\endash 2, \bullet  Punkt, \lquote einfach\rquote , ge\-trennt, Bindestrich\_fest, gesch\~utzt, Zeichen: \\ \{ \}\tab Ende\par}""",
        )
        assertEquals(
            "Gedanken—strich, von 1–2, • Punkt, ‘einfach’, getrennt, Bindestrich-fest, gesch utzt, Zeichen: \\ { }\tEnde",
            document.plainText,
        )
    }

    @Test
    fun boldItalicPlainAndGroups() {
        val document = read("""{\rtf1\ansi {\b fett {\i und kursiv}} normal \b\i beides\plain  zur\'fcck\b0\par}""")
        assertEquals(
            listOf(
                TextSpan("fett ", bold = true),
                TextSpan("und kursiv", bold = true, italic = true),
                TextSpan(" normal "),
                TextSpan("beides", bold = true, italic = true),
                TextSpan(" zurück"),
            ),
            document.blocks.single().spans,
        )
    }

    @Test
    fun codepagesAndFontCharsets() {
        assertEquals("Привет", read("""{\rtf1\ansi\ansicpg1251 \'cf\'f0\'e8\'e2\'e5\'f2\par}""").plainText)
        assertEquals("あい", read("""{\rtf1\ansi\ansicpg932 \'82\'a0\'82\'a2\par}""").plainText)
        assertEquals("Café", read("""{\rtf1\mac Caf\'8e\par}""").plainText)
        assertEquals(
            "Ελλάδα und Grüße",
            read("""{\rtf1\ansi\ansicpg1252{\fonttbl{\f0\fcharset0 Arial;}{\f1\fcharset161 Arial Greek;}}\f1 \'c5\'eb\'eb\'dc\'e4\'e1\f0  und Gr\'fc\'dfe\par}""").plainText,
        )
    }

    @Test
    fun binaryDataCannotBreakTheGroupStructure() {
        assertEquals("Vor nach", read("""{\rtf1\ansi Vor{\pict\bin5 }{}\}} nach\par}""").plainText)
    }

    @Test
    fun backslashBeforeLineEndActsAsParagraph() {
        assertOutline(read("{\\rtf1\\ansi Eins\\\nZwei\\par}"), "Paragraph: Eins", "Paragraph: Zwei")
    }

    @Test
    fun truncatedFilesStillYieldTheirText() {
        val document = read("""{\rtf1\ansi Abgeschnitten \b fett""")
        assertEquals("Abgeschnitten **fett**", document.blocks.single().markup())
    }

    @Test
    fun invalidFilesThrowIOExceptionWithGermanMessage() {
        for (rtf in listOf("Hallo Welt", "{\\rtf1 " + "{".repeat(5_000))) {
            val error = assertThrows(IOException::class.java) { read(rtf) }
            assertEquals("Die Datei ist beschädigt oder kein gültiges RTF-Dokument.", error.message)
        }
    }

    @Test
    fun longDocumentsAreTruncated() {
        val document = read("{\\rtf1\\ansi " + (1..200).joinToString("") { "Absatz $it\\par\n" } + "}", maxChars = 100)
        assertTrue(document.truncated)
        assertTrue(document.blocks.sumOf { it.text.length } <= 100)
    }
}
