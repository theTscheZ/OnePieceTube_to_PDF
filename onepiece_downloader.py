import argparse
from scraper import get_chapter_image_urls, get_all_chapters, get_latest_chapter_number, save_chapter_as_pdf


def onepiece_downloader():
    parser = argparse.ArgumentParser(description="OnePiece Manga Scraper")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("-a", "--all", action="store_true", help="Lade alle Kapitel")
    group.add_argument("-n", "--new", action="store_true", help="Lade das neueste Kapitel")
    group.add_argument("-c", "--chapter", type=int, help="Lade ein bestimmtes Kapitel")
    group.add_argument("-fc", "--from_chapter", type=int, help="Lade ab einem bestimmten Kapitel")
    group.add_argument("-r", "--range", nargs=2, type=int, metavar=('START', 'END'),
                       help="Lade eine Kapitel-Range, z.B. --range 420 430")

    args = parser.parse_args()

    if args.all:
        # Funktion für alle Kapitel
        print("Lade alle Kapitel...")
        get_all_chapters()

    elif args.new:
        # Funktion für das neueste Kapitel
        print("Lade das neueste Kapitel...")
        chapter_number = get_latest_chapter_number()
        save_chapter_as_pdf(chapter_number, get_chapter_image_urls(chapter_number))

    elif args.chapter:
        # Funktion für ein bestimmtes Kapitel
        chapter_number = args.chapter
        print(f"Lade Kapitel {chapter_number}...")
        save_chapter_as_pdf(chapter_number, get_chapter_image_urls(chapter_number))

    elif args.from_chapter:
        # Funktion für ab einem bestimmten Kapitel
        chapter_number = args.from_chapter
        print(f"Lade Kapitel {chapter_number}...")
        get_all_chapters(chapter_number)

    elif args.range:
        # Funktion für eine Range an Kapiteln
        start, end = args.range
        if start > end:
            print("Startkapitel muss kleiner oder gleich Endkapitel sein.")
            return
        print(f"Lade Kapitel {start} - {end}...")
        get_all_chapters(start, end)

    else:
        print("Benutze -a, -n, -c <nummer>, -fc <nummer> oder -r <nummer> <nummer> um Kapitel zu laden.")

if __name__ == "__main__":
    onepiece_downloader()
