# OnePieceTube Reader

Native Android-App zum Herunterladen, Verwalten und Lesen von OnePieceTube-Kapiteln als PDF.
Entwickelt mit Kotlin, Jetpack Compose und Material 3. Android 8.0 oder neuer erforderlich.

## Download und Installation

**[OnePieceTube Reader 1.1.0 – APK direkt herunterladen](https://github.com/theTscheZ/OnePieceTube_to_PDF/releases/download/v1.1.0/OnePieceTube-Reader-1.1.0.apk)**

APK auf dem Handy herunterladen und öffnen. Falls Android nachfragt, die Installation für den
verwendeten Browser oder Dateimanager erlauben. Für Updates die neue APK über die vorhandene App
installieren; bei einer Deinstallation werden intern gespeicherte PDFs und Lesestände gelöscht.
Updates müssen mit demselben Schlüssel signiert sein.

[Alle Versionen und Release-Informationen](https://github.com/theTscheZ/OnePieceTube_to_PDF/releases)

## Funktionen

- Optional mit **Halte mich aktuell** neue Kapitel automatisch herunterladen und benachrichtigen lassen.
- Lesestatus filtern und gelesene Kapitel gesammelt löschen.
- Akzentfarbe in den Einstellungen anpassen.
- Kapitel herunterladen: neuestes Kapitel, einzelnes Kapitel, Bereich oder ab einer Kapitelnummer.
- PDFs importieren, suchen, sortieren, umbenennen, teilen und exportieren.
- Mehrere Kapitel per langem Drücken und Wischen auswählen und gemeinsam löschen.
- Vollbild-Reader mit einer vollständigen Seite pro Bildschirm, Zoom und Leserichtung von rechts nach links.
- Nahtlos zum nächsten Kapitel weiterswischen und später am gespeicherten Lesestand fortsetzen.

## Entwicklung

Entwicklungsstand: **1.1.0**. Die neuen Funktionen werden mit dieser Version verfügbar;
der Download oben verweist bis zur nächsten Veröffentlichung auf den bestehenden Release 1.0.6.

Dieser Branch `codex/android-manga-reader` enthält die Android-App. Das separate Python-CLI
wird auf [`main`](https://github.com/theTscheZ/OnePieceTube_to_PDF/tree/main) gepflegt.

Das Projekt liegt unter [`android/`](android/). Diesen Ordner in Android Studio öffnen.
Build, Tests, Signierung und ausführliche Bedienung stehen in der [Android-Anleitung](android/README.md).
Die App lädt Kapitel nativ und benötigt weder Python noch einen Server.

## Hinweis

Inoffizielles Fanprojekt, nicht mit OnePieceTube verbunden. Bitte unterstütze die offiziellen Publisher.
