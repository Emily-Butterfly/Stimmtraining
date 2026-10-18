package de.emilybutterfly.stimmtraining.backup

import de.emilybutterfly.stimmtraining.data.Recording
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFormatTest {
    private val sample = Recording(
        id = 42,
        uuid = "0f8fad5b-d9cb-469f-a165-70867728950e",
        recordedAt = 1_791_000_000_000,
        durationMs = 83_500,
        audioFile = "0f8fad5b-d9cb-469f-a165-70867728950e.m4a",
        photoFile = "0f8fad5b-d9cb-469f-a165-70867728950e_1791000000000.jpg",
        title = "Der Nordwind und die Sonne",
        exercise = "Text lesen",
        warmedUp = true,
        warmUpMinutes = 10,
        voiceCondition = 4,
        selfRating = 3,
        notes = "Resonanz vorne, \"heller\" Klang",
        updatedAt = 1_791_000_100_000,
    )

    @Test
    fun manifestRoundTripKeepsAllParameters() {
        val json = BackupFormat.manifest(listOf(sample), createdAt = 1L, appVersion = "1.0")
        val parsed = BackupFormat.parse(json).single().recording
        assertEquals(sample.copy(id = 0), parsed)
    }

    @Test
    fun roundTripWithoutOptionalValues() {
        val minimal = sample.copy(photoFile = null, warmedUp = false, warmUpMinutes = null, voiceCondition = null, selfRating = null)
        val json = BackupFormat.manifest(listOf(minimal), createdAt = 1L, appVersion = "1.0")
        val parsed = BackupFormat.parse(json).single().recording
        assertNull(parsed.photoFile)
        assertNull(parsed.warmUpMinutes)
        assertNull(parsed.voiceCondition)
        assertNull(parsed.selfRating)
        assertEquals(minimal.copy(id = 0), parsed)
    }

    @Test
    fun documentRoundTrip() {
        val withDocument = sample.copy(photoFile = null, documentFile = "${sample.uuid}_1.docx", documentName = "Nordwind.docx")
        val json = BackupFormat.manifest(listOf(withDocument), createdAt = 1L, appVersion = "1.0")
        assertEquals(withDocument.copy(id = 0), BackupFormat.parse(json).single().recording)
    }

    @Test
    fun version1BackupsWithoutDocumentsStillParse() {
        val json = JSONObject(BackupFormat.manifest(listOf(sample), 1L, "1.0")).put("version", 1)
        val entry = json.getJSONArray("recordings").getJSONObject(0)
        entry.remove("document")
        entry.remove("documentName")
        val parsed = BackupFormat.parse(json.toString()).single().recording
        assertNull(parsed.documentFile)
        assertNull(parsed.documentName)
        assertEquals(sample.copy(id = 0), parsed)
    }

    @Test
    fun unknownDocumentTypesAreDroppedAndNamesCleaned() {
        val json = JSONObject(BackupFormat.manifest(listOf(sample), 1L, "1.0"))
        val list = json.getJSONArray("recordings")
        list.getJSONObject(0).put("document", "documents/x.exe").put("documentName", "x.exe")
        list.put(JSONObject(list.getJSONObject(0).toString()).put("document", "documents/y.pdf").put("documentName", "Zeile\nzwei.pdf"))
        list.put(JSONObject(list.getJSONObject(0).toString()).put("document", "documents/z.md").put("documentName", JSONObject.NULL))
        val parsed = BackupFormat.parse(json.toString()).map { it.recording }
        assertNull(parsed[0].documentFile)
        assertNull(parsed[0].documentName)
        assertEquals("y.pdf", parsed[1].documentFile)
        assertEquals("Zeilezwei.pdf", parsed[1].documentName)
        assertEquals("Text.md", parsed[2].documentName)
    }

    @Test
    fun pitchAnalysisRoundTripAndImplausibleValuesAreDropped() {
        val analysed = sample.copy(pitchMostFrequent = 57, pitchLowest = 52, pitchHighest = 62, pitchVoicedMs = 12_345)
        val noVoice = sample.copy(uuid = "1f8fad5b-d9cb-469f-a165-70867728950e", pitchVoicedMs = 120)
        val json = JSONObject(BackupFormat.manifest(listOf(analysed, noVoice, sample), 1L, "1.0"))
        val list = json.getJSONArray("recordings")
        list.put(JSONObject(list.getJSONObject(0).toString()).put("pitch", JSONObject().put("mostFrequent", 500).put("lowest", 1).put("highest", 2).put("voicedMs", 10)))
        list.put(JSONObject(list.getJSONObject(0).toString()).put("pitch", JSONObject().put("mostFrequent", 57).put("voicedMs", 10)))
        list.put(JSONObject(list.getJSONObject(0).toString()).put("pitch", JSONObject().put("mostFrequent", 57).put("lowest", 60).put("highest", 62).put("voicedMs", 10)))
        val parsed = BackupFormat.parse(json.toString()).map { it.recording }
        assertEquals(analysed.copy(id = 0), parsed[0])
        assertEquals(noVoice.copy(id = 0), parsed[1])
        assertNull(parsed[2].pitchVoicedMs)
        assertNull("unplausible Note", parsed[3].pitchVoicedMs)
        assertNull("unvollständig", parsed[4].pitchVoicedMs)
        assertNull("tiefste über häufigster Note", parsed[5].pitchVoicedMs)
    }

    @Test
    fun rejectsForeignFiles() {
        assertThrows(BackupException::class.java) { BackupFormat.parse("""{"format":"other","recordings":[]}""") }
        assertThrows(BackupException::class.java) { BackupFormat.parse("kein json") }
    }

    @Test
    fun rejectsNewerVersions() {
        val json = JSONObject(BackupFormat.manifest(emptyList(), 1L, "1.0")).put("version", 99).toString()
        assertThrows(BackupException::class.java) { BackupFormat.parse(json) }
    }

    @Test
    fun entryNamesCannotEscapeTheBackup() {
        assertEquals("manifest.json", BackupFormat.safeEntryName("manifest.json"))
        assertEquals("audio/a.m4a", BackupFormat.safeEntryName("audio/a.m4a"))
        assertEquals("photos/b.jpg", BackupFormat.safeEntryName("/photos/b.jpg"))
        assertEquals("documents/c.pdf", BackupFormat.safeEntryName("documents/c.pdf"))
        assertNull(BackupFormat.safeEntryName("documents/../c.pdf"))
        assertNull(BackupFormat.safeEntryName("audio/../../databases/x.db"))
        assertNull(BackupFormat.safeEntryName("audio/..\\..\\x"))
        assertNull(BackupFormat.safeEntryName("audio/sub/dir.m4a"))
        assertNull(BackupFormat.safeEntryName("audio/.hidden"))
        assertNull(BackupFormat.safeEntryName("other/file.txt"))
    }

    @Test
    fun entriesWithUnsafePathsAreSkippedAndBadUuidsReplaced() {
        val json = JSONObject(BackupFormat.manifest(listOf(sample), 1L, "1.0"))
        val list = json.getJSONArray("recordings")
        list.put(JSONObject(list.getJSONObject(0).toString()).put("audio", "audio/../evil"))
        list.put(JSONObject(list.getJSONObject(0).toString()).put("uuid", "../../x").put("photo", "../x.jpg"))
        val parsed = BackupFormat.parse(json.toString())
        assertEquals(2, parsed.size)
        val repaired = parsed[1].recording
        assertTrue(repaired.uuid.matches(Regex("[0-9a-f-]{36}")))
        assertNull(repaired.photoFile)
    }
}
