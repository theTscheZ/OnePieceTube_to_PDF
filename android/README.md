# OnePieceTube Reader — Android

Inoffizielle App für Downloads und PDF-Lesen von onepiece.tube.

Native Android-App für dieses Repository, mit Kotlin, Jetpack Compose und Material 3.
Android 8.0 (API 26) oder neuer. Kein Python, PC, Server oder Account auf dem Handy nötig.

## Installation / APK bauen

Öffne diesen `android/`-Ordner in Android Studio, lass Gradle synchronisieren und starte `app`
auf deinem Handy. Benötigt werden JDK 17 und das Android SDK 35.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows: `gradlew.bat testDebugUnitTest lintDebug assembleDebug`.
Die installierbare Test-APK liegt danach unter `app/build/outputs/apk/debug/app-debug.apk`.
Es ist eine Debug-APK mit dem lokalen Debug-Schlüssel, keine für den Play Store signierte Veröffentlichung.

Der GitHub-Actions-Workflow **Android APK** führt dieselben Prüfungen aus und stellt bei Erfolg
`OnePieceTube-Reader-debug-apk` als herunterladbares Artefakt bereit. Bei fehlgeschlagenen Prüfungen
entsteht bewusst kein als erfolgreich gekennzeichnetes APK-Artefakt.

## Bedienung

- **+**: eine oder mehrere vorhandene PDFs importieren. Die App erstellt lokale Kopien.
- **Kapitel laden**: neuestes Kapitel, einzelnes Kapitel, Bereich, ab einem Kapitel oder alle ab 420
  (entspricht dem Startwert des Python-Scripts). Ein Download-Auftrag gleichzeitig.
- Die Startnummer für „Ein Kapitel“ und „Ab Kapitel“ ist die höchste vorhandene Kapitelnummer + 1;
  ohne erkennbare Kapitel bleibt der bisherige Startwert 420. Kopierzusätze wie `(2)` werden ignoriert.
  „Bereich“ hat separate Eingaben mit den festen Anfangswerten 420–430.
- Downloads laufen über WorkManager mit Fortschritt und Abbruch in App/Benachrichtigung.
  Ohne Verbindung wartet der Auftrag auf Internet. Android kann Hintergrundarbeit verzögern oder beenden;
  sehr große Sammlungen daher besser in kleineren Bereichen laden. Nach einem Abbruch denselben Bereich
  erneut starten: fertige Dateien werden übersprungen.
- Erfolgs-, Fehler- und Abbruchmeldungen verwenden automatisch verschwindende Material-3-Snackbars
  (standardmäßig ca. 4 Sekunden, mit Androids Einstellung für Bedienungshilfen gegebenenfalls länger).
  Alte Download-Ergebnisse erscheinen nach einem Neustart nicht erneut.
- Kapitel lange drücken: Auswahlmodus. Antippen wählt einzelne Kapitel aus/ab.
  Nach dem langen Drücken gedrückt halten und über Kapitel wischen: zusammenhängenden Bereich auswählen;
  beim Zurückwischen wird der Bereich wieder kleiner. Start auf einem ausgewählten Kapitel wählt den
  gewischten Bereich ab. Am oberen/unteren Listenrand scrollt die Liste automatisch, zum Rand hin schneller.
  Die Leiste bietet X/Android-Zurück zum Verlassen, alle angezeigten Kapitel auswählen/abwählen (auch außerhalb
  des Bildschirms, unter Berücksichtigung der aktuellen Suche) und Löschen nach einer Bestätigung.
  Importierte Originaldateien bleiben erhalten. Fehlgeschlagene Löschungen bleiben ausgewählt.
- Suche nach Dateiname/Kapitel; Sortierung aufsteigend, absteigend, nach Datum oder Größe.
- **⋮** an einer PDF: öffnen, umbenennen, teilen/extern öffnen, exportieren oder nach Bestätigung löschen.
  Export nutzt Androids Dateiauswahl. Importierte Originaldateien werden nicht verändert oder gelöscht.
- **Weiterlesen** öffnet die zuletzt gelesene PDF auf der gespeicherten Seite.

### Reader

Eine vollständige PDF-Seite pro Bildschirm, ohne Abschneiden oder Strecken. Bei abweichendem
Seitenformat bleiben schwarze Ränder. Doppelseiten, die in der PDF eine Seite bilden, bleiben zusammen.
Status- und Navigationsleisten sind ausgeblendet; das Display bleibt während des Lesens an.

**Nach rechts wischen = nächste Seite**, nach links = vorherige Seite. Nach der letzten Seite
geht es ohne zusätzlichen Dialog auf Seite 1 der nächsten vorhandenen PDF. Rückwärts geht es
auf die letzte Seite der vorherigen PDF. Fehlende Kapitel werden nicht automatisch heruntergeladen.
Die Lesereihenfolge ist stets die natürliche aufsteigende Dateinamenreihenfolge (`2` vor `10`),
unabhängig von Suche oder Anzeige-Sortierung. Für eigene Dateien sinnvolle Kapitelnummern im Namen verwenden.

Pinch-Zoom (1–5×), Verschieben im Zoom und Doppeltippen (Zoom/Einpassen). Im Zoom blättern Wischgesten
nicht, damit beim Verschieben keine Seite versehentlich wechselt. Zum Blättern herauszoomen oder
kurz tippen und die Pfeile benutzen. Einmal tippen blendet die Bedienelemente ein/aus.
Die Kapitelnummer steht oben mittig in Akzentfarbe; das X rechts oben schließt den Reader.
Die Seitenzahl öffnet einen direkten Seitensprung. Zurück schließt zuerst die Leisten, danach den Reader.
Der Lesestand wird pro PDF gespeichert und beim Umbenennen mitgenommen.

## Verhältnis zum Python-Script

`onepiece_downloader.py` und `scraper.py` bleiben unverändert nutzbar. Die App führt nicht die Python-Datei aus,
sondern implementiert dieselben Download-Modi nativ. Grund: Playwright/Chromium ist keine geeignete
Android-Laufzeit. Zukünftige Änderungen an Python und Android müssen entsprechend getrennt gepflegt werden.

Die Website liefert aktuell `window.__data` mit `chapter.pages` und `categoryEntries`. Die App liest
Seitenadressen und Kapitelnummern daraus, ohne JavaScript auszuführen. Als Fallback unterstützt sie die
alten DOM-Selektoren des Scripts. Es werden keine Bildadressen geraten, und Bild-Reihenfolge/Doppelseiten
bleiben erhalten. Ändert die Website ihre Struktur oder blockiert Zugriffe, zeigt die App einen Fehler.

Die PDF-Erstellung schreibt Seiten einzeln auf die Festplatte, damit nicht ein ganzes Kapitel als
Bitmap-Sammlung im Arbeitsspeicher liegen muss. Bilder werden mit JPEG-Qualität 95 eingebettet;
Bilder über 12 Megapixel werden zum Speicherschutz verkleinert.

Jede PDF wird zunächst als temporäre Datei geschrieben, geprüft und erst dann veröffentlicht. Fehlerhafte
Bilder führen zum Abbruch des Kapitels statt zu einer unvollständigen PDF. Bereits vorhandene Kapitel
werden übersprungen. Quelldateien/PDFs werden ausschließlich lokal gespeichert. Keine Analyse- oder Cloud-Dienste.
App-interne PDFs und Lesestand werden bei Deinstallation gelöscht; gewünschte PDFs vorher exportieren.

## Prüfungen

Unit-Tests decken natürliche Kapitelreihenfolge, große Zahlen, Vorwärts-/Rückwärtsübergänge und
Sammlungsgrenzen ab, außerdem eingebettete JSON-Daten, Doppelseiten, alte Pagination,
relative Bildadressen und Fehlermeldungen bei unbrauchbaren Antworten.

Manuelle Geräteprüfung vor einer Veröffentlichung:

1. PDFs mit unterschiedlichen Seitenzahlen importieren; beschädigte PDF und Namensduplikat probieren.
2. Kapitel 9 und 10: vorwärts/rückwärts über die Grenze wischen, auch bei langsamen Wischgesten.
3. Pinch, Verschieben bis zu allen Rändern, Doppeltippen; im Zoom darf kein Wisch blättern.
4. Hoch-/Querformat und Display-Ausschnitt, Leisten ein/aus, Zurück, App neu starten, Lesestand prüfen.
5. Suche, vier Sortierungen, Umbenennen, Export/Teilen, Löschbestätigung und Originaldatei prüfen.
6. Kleinen Kapitelbereich laden, App in Hintergrund, abbrechen, erneut starten, Offline/HTTP-Fehler prüfen.
7. Große/Doppelseiten und geschützte PDFs testen. Passwortgeschützte PDFs werden nicht unterstützt.

Der Reader rendert nur eine Seite gleichzeitig auf einem Hintergrund-Thread, mit begrenzter
Bitmap-Auflösung (maximal 2400 × 3200). Zoom vergrößert diese Bitmap; es ist kein unbegrenztes
Vektor-Neurendern. Keine Seitenbearbeitung wie Zusammenführen, Ausschneiden oder Neuordnen innerhalb einer PDF.

## Signierung von Updates

Eine APK kann eine vorhandene Installation nur aktualisieren, wenn sie mit demselben Schlüssel
signiert wurde. Ein neuer temporärer Debug-Schlüssel reicht dafür nicht. Signierschlüssel privat
und dauerhaft sichern, niemals ins Repository aufnehmen. Vor einer nötigen Neuinstallation PDFs
exportieren; eine Deinstallation entfernt die app-internen Dateien und den Lesestand.
