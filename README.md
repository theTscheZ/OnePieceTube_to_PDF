# OnePiece Manga PDF Downloader

Ein CLI-Tool zum Herunterladen von One Piece Manga-Kapiteln als PDF direkt von [onepiece.tube](https://onepiece.tube).

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

