# OnePieceTube Reader für iPhone und iPad

Native SwiftUI-App mit PDFKit. Benötigt iOS/iPadOS 17 oder neuer. Die App lädt Kapitel direkt
und benötigt weder Python noch einen eigenen Server. Inoffizielles Fanprojekt, nicht mit OnePieceTube verbunden.

## Installation

Die iOS-Version wird unabhängig von der Android-APK gebaut. Ein iPhone installiert keine APKs.
Die von GitHub Actions erzeugte `OnePieceTubeReader-1.0.0-unsigned.ipa` ist **noch nicht signiert**
und kann nicht durch bloßes Antippen installiert werden. Sie muss für das jeweilige Apple-Konto
und die Geräte signiert/provisioniert werden.

Für eine eigene Installation über Xcode:

1. Auf einem Mac Xcode 16 oder neuer installieren und unter **Settings → Apple Accounts** anmelden.
2. XcodeGen installieren und das Projekt erzeugen:

   ```bash
   brew install xcodegen
   cd ios
   xcodegen generate
   open OnePieceTubeReader.xcodeproj
   ```

3. Target **OnePieceTubeReader → Signing & Capabilities** öffnen. **Automatically manage signing**
   aktiv lassen und das eigene Team auswählen. Falls die Bundle-ID bereits vergeben ist,
   `at.aaron.onepiece.ios` durch eine eigene eindeutige ID ersetzen. Dann auch den Hintergrundtask-Identifier
   in `project.yml` und `OnePieceTubeReaderApp.swift` entsprechend anpassen.
4. Das iPhone verbinden, als Ziel auswählen und mit **Run** starten. Wenn iOS danach fragt,
   **Entwicklermodus** aktivieren und dem Entwickler vertrauen.

Ein persönliches Apple-Konto genügt zum eigenen Entwicklertest; diese Installation muss regelmäßig
erneut provisioniert werden. Für Verteilung über TestFlight ist eine Apple-Developer-Program-Mitgliedschaft
und die Einrichtung in App Store Connect erforderlich. Apple-Signierschlüssel gehören niemals ins Repository.

## Funktionen und Bedienung

- **+** importiert eine oder mehrere PDFs als eigene lokale Kopien.
- **Kapitel laden** bietet neuestes Kapitel, einzelnes Kapitel, Bereich, ab einem Kapitel und alle ab 420.
  Einzel-/Ab-Standard ist die höchste erkennbare vorhandene Kapitelnummer + 1; Bereich bleibt 420–430.
- Suche, natürliche Kapitelreihenfolge, umgekehrte Reihenfolge, Datum und Dateigröße.
- **…** an einem Kapitel: öffnen, umbenennen, als gelesen/angefangen/ungelesen markieren, teilen oder löschen.
  **Teilen / In Dateien sichern** nutzt das iOS-Freigabemenü zum Exportieren.
- Lange drücken startet die Auswahl. Gedrückt halten und über die Karten ziehen wählt einen Bereich;
  beim Zurückziehen schrumpft er wieder. Am oberen/unteren Rand scrollt die Sammlung automatisch,
  näher am Rand schneller. Die Leiste bietet X, alle sichtbaren Such-/Filtertreffer auswählen/abwählen,
  Lesestatus und Löschen mit Bestätigung. Ursprünglich importierte Dateien bleiben erhalten.
- **Weiterlesen** setzt die gespeicherte Seite fort. Statusfilter stehen oberhalb der Sammlung.
- Das Bibliotheksmenü bietet **Gelesene Kapitel löschen**, ebenfalls mit Bestätigung.
- **Zahnrad**: sechs sofort wirksame Akzentfarben und **Halte mich aktuell**, anfangs ausgeschaltet.

### Reader

Eine vollständige PDF-Seite pro Bildschirm. Das Seitenformat bleibt erhalten; freie Bereiche sind schwarz.
Nach rechts wischen bedeutet nächste Seite, nach links vorherige Seite. Nach der letzten Seite folgt
direkt das nächste vorhandene Kapitel; rückwärts geht es entsprechend ins vorige Kapitel.
Die Reader-Reihenfolge bleibt unabhängig von Suche und Bibliothekssortierung aufsteigend.
Beschädigte oder gesperrte PDFs werden beim Blättern übersprungen.

PDFKit bietet Pinch-Zoom bis 5× und Verschieben im Zoom. Solange vergrößert wird, bleibt das
Blättern gesperrt, damit Verschieben nicht versehentlich die Seite wechselt. Herauszoomen oder
die eingeblendeten Pfeile verwenden, um weiterzublättern.
Tippen blendet die Leisten ein/aus: mittig **Kapitel X**, rechts oben X, unten Seitenzahl und Navigation.
Die Seitenzahl öffnet einen direkten Seitensprung. Lesestände werden pro PDF gespeichert;
die letzte Seite markiert ein Kapitel als gelesen. „Als ungelesen markieren“ setzt die Seite zurück.

### Automatische Downloads

Beim Aktivieren von **Halte mich aktuell** wird sofort geprüft. Ohne erkennbare Kapitel wird nur
das neueste geladen; sonst alles nach der höchsten Kapitelnummer. Erfolgreiche Downloads werden
zusätzlich gemerkt, damit später gelöschte Kapitel nicht immer wieder erscheinen.
**Nur im WLAN** ist voreingestellt und verbietet mobile, teure und eingeschränkte Verbindungen für die Automatik.
Manuelle Downloads können mobile Daten verwenden.

Danach wird ein `BGProcessingTask` frühestens für eine Stunde später angemeldet. iOS entscheidet,
wann und ob er tatsächlich ausgeführt wird; ein fester Stundentakt oder sofortige Benachrichtigung
ist ohne eigenen Push-Dienst nicht zugesichert. Hintergrundaktualisierung muss für die App erlaubt sein.
Wenn iOS die Zeit beendet, werden laufende Anfragen abgebrochen und temporäre Dateien entfernt.
Vollständig gespeicherte Kapitel bleiben erhalten; der nächste Lauf setzt danach fort.
Manuelle Downloads sollten bei geöffneter App laufen, insbesondere größere Kapitelbereiche.

Nur erfolgreich **automatisch** geladene Kapitel erzeugen **Neues Kapitel X**; Antippen öffnet das PDF.
Manuelle Downloads melden ihren Abschluss kurz in der Bibliothek. Verweigerte Benachrichtigungen
verhindern keine Downloads. Alle Meldungen in der Bibliothek verschwinden automatisch nach 4 Sekunden,
bei aktivem VoiceOver nach 8 Sekunden.

## Build und Tests

`project.yml` ist die Projektdefinition; XcodeGen erzeugt das Xcode-Projekt und `Info.plist`.
Es gibt keine externen App-Bibliotheken. SwiftUI, PDFKit, URLSession, BackgroundTasks und lokale
Benachrichtigungen stammen aus dem iOS SDK.

```bash
cd ios
xcodegen generate
xcodebuild -project OnePieceTubeReader.xcodeproj -scheme OnePieceTubeReader \
  -destination 'platform=iOS Simulator,name=iPhone 16' CODE_SIGNING_ALLOWED=NO test
```

Falls dieser Simulator nicht installiert ist, mit `xcrun simctl list devices available` einen
verfügbaren iPhone-Namen auswählen. Der [iPhone-Workflow](../.github/workflows/ios.yml) wählt automatisch
ein verfügbares iPhone, führt Unit-/UI-Tests aus und baut danach eine unsignierte IPA für echte Geräte.
Die IPA und der `.xcresult`-Testbericht stehen als GitHub-Actions-Artefakte bereit.

Tests prüfen Kapitelreihenfolge, Dateinamen, Download-Bereiche, Fehlerantworten, Bildreihenfolge,
Lesestatus, Importkopien, Umbenennen/Löschen, Einstellungen und Ziehauswahl. UI-Tests starten die App,
öffnen die Einstellungen, wählen eine Farbe, prüfen den Auswahlmodus und blättern nach rechts ins nächste PDF.
Die Test-PDFs und Einstellungen liegen beim speziellen Debug-Teststart in einem getrennten Verzeichnis.

Vor Verteilung auf einem echten iPhone prüfen: Pinch/Pan, Drehung, Aussparung und Home-Indikator,
große/geschützte PDFs, lange Sammlungen mit Rand-Scrolling, echte Kapitel-Downloads,
Hintergrundbeendigung, Wiederaufnahme und Benachrichtigungsberechtigung.

PDFs und Lesestände liegen lokal im App-Container. Eine Deinstallation entfernt diese Daten;
gewünschte PDFs vorher über **In Dateien sichern** exportieren. Es gibt keine Telemetrie oder Cloud-Synchronisation.
Die Oberfläche verwendet native iOS-Formulare, Navigation, Suchfeld und Dialoge sowie eine gemeinsame
Abstandsskala von 8/16/24 Punkten für eigene Layouts.
