# Stimmtraining

Android-App für das Stimmtraining zur Feminisierung der Stimme: Aufnahmen machen, zu jeder
Aufnahme festhalten, unter welchen Bedingungen sie entstanden ist, und alles sicher aufbewahren.

## Funktionen

- **Aufnehmen** über das Mikrofon (AAC/.m4a, 44,1 kHz) mit Pause/Fortsetzen, Zeitanzeige und
  Pegelanzeige. Der Bildschirm bleibt während der Aufnahme an. Danach direkt anhören, neu
  aufnehmen oder speichern.
- **Datum und Uhrzeit** werden beim Start der Aufnahme automatisch erfasst.
- **Parameter** zu jeder Aufnahme:
  - vorher aufgewärmt (ja/nein) und Aufwärmdauer in Minuten
  - Übung (frei oder per Vorschlag: Text lesen, Spontan sprechen, Resonanz, Tonhöhe, …)
  - stimmliche Tagesform (1–5)
  - Zufriedenheit mit der Aufnahme (1–5)
  - Titel/Text und Notizen
- **Text zum Lesen**, wahlweise
  - als **Foto**: mit der Kamera aufnehmen oder aus der Galerie wählen. Fotos werden
    automatisch richtig gedreht und verkleinert.
  - als **Datei**: PDF, Word (.docx), OpenDocument (.odt), RTF, HTML, Markdown (.md) oder
    Text (.txt), bis 50 MB. Das Format erkennt die App am Inhalt. Alte Word-Dateien (.doc)
    gehen nicht – in Word oder LibreOffice als .docx oder PDF speichern.
  - **eingefügt** per Kopieren und Einfügen (oder getippt), bis 50.000 Zeichen. Eingefügte
    Texte und Textdateien lassen sich später bearbeiten.

  Der Text wird beim Aufnehmen angezeigt. Schriftgröße bzw. PDF-Zoom lassen sich mit Knöpfen
  oder zwei Fingern einstellen; die Schriftgröße merkt sich die App. Der **Lesemodus** zeigt
  den Text auf dem ganzen Bildschirm, die Aufnahme-Knöpfe bleiben sichtbar. Dateien lassen
  sich außerdem in einer anderen App öffnen.
- **Tonhöhen-Analyse**: ermittelt für eine Aufnahme die häufigste, die tiefste und die
  höchste Note der Sprechstimme (z. B. A3 = 220 Hz; das mittlere C heißt C4). Kurze Ausreißer
  zählen nicht. Die Analyse läuft vollständig auf dem Handy; das Ergebnis wird gespeichert, in
  der Übersicht angezeigt und mitgesichert.
- **Speicherplatz**: Sind weniger als 50 MB frei, weist die App vor dem Aufnehmen darauf hin
  (mit der ungefähren Aufnahmezeit, die noch passt). Wird der Speicher während einer Aufnahme
  knapp, endet sie rechtzeitig und bleibt abspielbar. Eine Minute Aufnahme braucht knapp 1 MB.
- **Übersicht** aller Aufnahmen nach Tagen, mit Detailansicht, Bearbeiten, Teilen und Löschen.
- **Backups**:
  - *Backup erstellen*: alle Aufnahmen, Fotos, Dateien und Angaben als ZIP-Datei an einem Ort
    deiner Wahl speichern (z. B. Google Drive, Downloads, USB-Stick).
  - *Backup wiederherstellen*: ergänzt nur fehlende Aufnahmen, überschreibt oder löscht nichts.
    Damit klappt auch der Umzug auf ein neues Handy.
  - *Automatisches Backup*: täglich oder wöchentlich in einen gewählten Ordner, nur wenn sich
    etwas geändert hat. Die letzten 5 automatischen Sicherungen
    (`Stimmtraining-Auto-Backup-….zip`) bleiben erhalten; andere Dateien im Ordner, z. B.
    manuelle Sicherungen, werden nie gelöscht.

Alle Daten bleiben auf dem Handy, es gibt keinen Server und kein Konto. Die App hat **keine
Internet-Berechtigung** – sie kann Aufnahmen gar nicht verschicken; ein Test stellt sicher,
dass das so bleibt. Nur was du selbst teilst oder sicherst, verlässt das Gerät. Androids eigenes
Cloud-Backup ist bewusst aus (Audiodateien sprengen dessen 25-MB-Grenze). Die Direktübertragung
beim Gerätewechsel nimmt die Daten nur mit, wenn das alte Handy Android 12 oder neuer hat –
**vor einem Handywechsel daher immer „Backup erstellen“ und auf dem neuen Handy wiederherstellen.**
Das automatische Backup muss auf dem neuen Gerät neu eingerichtet werden (Ordner wählen).

## Installieren

Jeder Push baut die App automatisch mit GitHub Actions.

- Auf dem Standard-Branch wird die APK als **Release** veröffentlicht: Auf GitHub unter
  *Releases* die neueste `Stimmtraining-….apk` auf dem Handy herunterladen und öffnen
  (Installation aus unbekannten Quellen erlauben).
- Für andere Branches liegt die APK unter *Actions* → Lauf → *Artifacts*.

### Updates ohne Datenverlust (einmalig einrichten)

Android installiert ein Update nur, wenn es mit demselben Schlüssel signiert ist wie die
installierte Version. Der Schlüssel liegt verschlüsselt im Repository
(`signing/stimmtraining.jks.enc`, AES-256); GitHub braucht nur das Passwort dazu:

1. Im Repository *Settings → Secrets and variables → Actions → New repository secret*.
2. Name: `STIMM_KEYSTORE_KEY`, Wert: das Passwort (24 Zeichen in vier Sechsergruppen).

Leerzeichen oder Zeilenumbrüche, die beim Kopieren hineingeraten, werden ignoriert. Im Log des
Builds und in den Release-Notizen steht, ob mit dem festen Schlüssel signiert wurde (mit
SHA-256-Fingerabdruck) oder mit einem Übergangsschlüssel.

Alternativ kann der ganze Keystore als Base64 im Secret `STIMM_KEYSTORE_BASE64` stehen
(Alias `stimmtraining`, Store- und Key-Passwort gleich, Standard `stimmtraining`, sonst als
`STIMM_KEYSTORE_PASSWORD`). Eigenen Schlüssel erzeugen:

```sh
keytool -genkeypair -keystore stimmtraining.jks -storetype PKCS12 -alias stimmtraining \
  -keyalg RSA -keysize 4096 -validity 36500 -storepass stimmtraining -keypass stimmtraining \
  -dname "CN=Stimmtraining"
base64 -w0 stimmtraining.jks   # Ausgabe als Secret STIMM_KEYSTORE_BASE64 eintragen
```

Ohne gültigen Schlüssel baut GitHub trotzdem eine APK, aber mit einem Übergangsschlüssel. Eine
spätere Version mit festem Schlüssel lässt sich dann nur nach Deinstallation installieren:
vorher in der App ein Backup erstellen, danach wiederherstellen.

## Entwickeln

- Kotlin, Jetpack Compose (Material 3), Room, WorkManager
- `minSdk` 26 (Android 8.0), `targetSdk` 36
- Bauen und testen: `./gradlew testDebugUnitTest assembleDebug`
- Die Tests (Robolectric) prüfen u. a. ein vollständiges Backup → neues Gerät → Wiederherstellen
  mit echter Datenbank, das Datenbank-Update von Version 1 auf 2, den Foto-Import (Drehung,
  Verkleinerung), Erkennen und Lesen aller Dateiformate, dass ein fehlgeschlagenes Speichern
  nichts verliert, dass ein Entwurf das Beenden der App übersteht, die Speicherplatz-Warnung,
  die Rotation der automatischen Backups, und sie öffnen jede Ansicht der App einmal.
- Änderungen an der Datenbank brauchen eine neue Version mit Migration in `AppDatabase`; das
  Schema jeder Version liegt unter `app/schemas/` und gehört ins Repository.

### Aufbau

```
app/src/main/java/de/emilybutterfly/stimmtraining/
  data/      Room-Datenbank, Dateiablage, Foto-Import
  data/document/  Dateien als Text: Formate erkennen, Text lesen, PDF-Seiten rendern
  analysis/  Tonhöhen-Analyse: Audio dekodieren, Grundfrequenz bestimmen, Noten zählen
  audio/     Aufnahme (MediaRecorder)
  backup/    ZIP-Format, Export/Import, automatisches Backup
  ui/        Compose-Ansichten: Liste, Aufnahme, Detail, Backup
```

Format der Sicherung: `manifest.json` (alle Aufnahmen mit Parametern), `audio/…`, `photos/…`,
`documents/…` (seit Format-Version 2; ältere Sicherungen lassen sich weiterhin einspielen).
