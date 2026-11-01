# OnePiece Manga PDF Downloader

Ein CLI-Tool zum Herunterladen von One Piece Manga-Kapiteln als PDF direkt von [onepiece.tube](https://onepiece.tube).

## Android-App herunterladen

**[OnePieceTube Reader 1.0.6 – APK direkt herunterladen](https://github.com/theTscheZ/OnePieceTube_to_PDF/releases/download/v1.0.6/OnePieceTube-Reader-1.0.6.apk)**

Ab Android 8.0: APK auf dem Handy herunterladen und öffnen. Falls Android nachfragt, die Installation für den verwendeten Browser oder Dateimanager erlauben.

[Alle Versionen und Release-Informationen](https://github.com/theTscheZ/OnePieceTube_to_PDF/releases)

## Features
- Lade einzelne oder mehrere One Piece Manga-Kapitel als PDF herunter.
- Unterstützt unter anderem das neueste Kapitel, bestimmte Kapitel oder eine Kapitel-Range
- Einfach zu bedienen – für schnelle Manga-Downloads!

## Installation
```bash
pip install -r requirements.txt
```

## Nutzung
```bash
python onepiece_downloader.py [Optionen]
```

### Optionen:
```
  -h, --help            Zeigt diese Hilfe an und beendet das Programm
  -a, --all             Lade alle Kapitel
  -n, --new             Lade das neueste Kapitel
  -c CHAPTER, --chapter CHAPTER
                        Lade ein bestimmtes Kapitel
  -fc FROM_CHAPTER, --from_chapter FROM_CHAPTER
                        Lade ab einem bestimmten Kapitel
  -r START END, --range START END
                        Lade eine Kapitel-Range, z.B. --range 420 430
```

## Beispiele
### Neuestes Kapitel laden
```bash
python onepiece_downloader.py --new
```

### Kapitel 1050 laden
```bash
python onepiece_downloader.py --chapter 1050
```

### Alle Kapitel laden
```bash
python onepiece_downloader.py --all
```

### Ab Kapitel 990 alle laden
```bash
python onepiece_downloader.py --from_chapter 990
```

### Kapitel 600 bis 610 laden
```bash
python onepiece_downloader.py --range 600 610
```

## Hinweis
Dieses Tool ist ausschließlich für Bildungs- und Archivierungszwecke gedacht. Bitte unterstütze die offiziellen Publisher, wenn du kannst!

---

Falls du Probleme hast oder Features vorschlagen willst, gerne bei mir melden.


## Android-App: OnePieceTube Reader

Die native Android-App liegt unter [`android/`](android/README.md): Kapitel direkt auf dem Handy
laden, PDFs importieren/verwalten/exportieren und offline im Vollbild lesen — mit Wischgesten,
Zoom, gespeicherter Leseposition und nahtlosen Wechseln zwischen Kapitel-PDFs.
Build und Installation sind in der [Android-Anleitung](android/README.md) beschrieben.
