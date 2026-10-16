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
- **Foto vom Text**: mit der Kamera aufnehmen oder aus der Galerie wählen. Es wird beim
  Aufnehmen angezeigt (zum Ablesen) und lässt sich im Vollbild zoomen. Fotos werden automatisch
  richtig gedreht und verkleinert.
- **Übersicht** aller Aufnahmen nach Tagen, mit Detailansicht, Bearbeiten, Teilen und Löschen.
- **Backups**:
  - *Backup erstellen*: alle Aufnahmen, Fotos und Angaben als ZIP-Datei an einem Ort deiner
    Wahl speichern (z. B. Google Drive, Downloads, USB-Stick).
  - *Backup wiederherstellen*: ergänzt nur fehlende Aufnahmen, überschreibt oder löscht nichts.
    Damit klappt auch der Umzug auf ein neues Handy.
  - *Automatisches Backup*: täglich oder wöchentlich in einen gewählten Ordner, nur wenn sich
    etwas geändert hat. Die letzten 5 automatischen Sicherungen
    (`Stimmtraining-Auto-Backup-….zip`) bleiben erhalten; andere Dateien im Ordner, z. B.
    manuelle Sicherungen, werden nie gelöscht.

Alle Daten bleiben auf dem Handy, es gibt keinen Server und kein Konto. Androids eigenes
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
installierte Version. Damit das klappt, braucht GitHub einen festen Schlüssel:

1. Im Repository *Settings → Secrets and variables → Actions → New repository secret*.
2. Name: `STIMM_KEYSTORE_BASE64`, Wert: der Base64-Text der Keystore-Datei.
3. Optional ein eigenes Passwort als `STIMM_KEYSTORE_PASSWORD` (Standard: `stimmtraining`).

Den Keystore selbst erzeugen (Alias muss `stimmtraining` sein, Store- und Key-Passwort gleich):

```sh
keytool -genkeypair -keystore stimmtraining.jks -storetype PKCS12 -alias stimmtraining \
  -keyalg RSA -keysize 4096 -validity 36500 -storepass stimmtraining -keypass stimmtraining \
  -dname "CN=Stimmtraining"
base64 -w0 stimmtraining.jks   # Ausgabe als Secret eintragen
```

Den Keystore gut aufheben und niemals ins Repository legen. Ohne das Secret ist jede APK anders
signiert; dann vor einem Update in der App ein Backup erstellen, die alte Version deinstallieren,
die neue installieren und das Backup wiederherstellen.

## Entwickeln

- Kotlin, Jetpack Compose (Material 3), Room, WorkManager
- `minSdk` 26 (Android 8.0), `targetSdk` 36
- Bauen und testen: `./gradlew testDebugUnitTest assembleDebug`
- Die Tests (Robolectric) prüfen u. a. ein vollständiges Backup → neues Gerät → Wiederherstellen
  mit echter Datenbank, den Foto-Import (Drehung, Verkleinerung), dass ein fehlgeschlagenes
  Speichern nichts verliert, dass ein Entwurf das Beenden der App übersteht, die Rotation der
  automatischen Backups, und sie öffnen jede Ansicht der App einmal.

### Aufbau

```
app/src/main/java/de/emilybutterfly/stimmtraining/
  data/      Room-Datenbank, Dateiablage, Foto-Import
  audio/     Aufnahme (MediaRecorder)
  backup/    ZIP-Format, Export/Import, automatisches Backup
  ui/        Compose-Ansichten: Liste, Aufnahme, Detail, Backup
```

Format der Sicherung: `manifest.json` (alle Aufnahmen mit Parametern), `audio/…`, `photos/…`.
