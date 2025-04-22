import argparse
import requests
from scraper import get_chapter_image_urls, get_all_chapters#, get_latest_chapter


def main():
    parser = argparse.ArgumentParser(description="OnePiece Manga Scraper")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("-a", "--all", action="store_true", help="Lade alle Kapitel")
    group.add_argument("-n", "--new", action="store_true", help="Lade das neueste Kapitel")
    group.add_argument("-c", "--chapter", type=int, help="Lade ein bestimmtes Kapitel")
    group.add_argument("-s", "--from_chapter", type=int, help="Lade ab einem bestimmten Kapitel")

    args = parser.parse_args()

    if args.all:
        # Funktion für alle Kapitel
        print("Lade alle Kapitel...")
        get_all_chapters()

    elif args.new:
        # Funktion für das neueste Kapitel
        print("Lade das neueste Kapitel...")
        #get_latest_chapter()

    elif args.chapter:
        # Funktion für ein bestimmtes Kapitel
        chapter_number = args.chapter
        print(f"Lade Kapitel {chapter_number}...")
        get_chapter_image_urls(chapter_number)

    elif args.from_chapter:
        # Funktion für ab einem bestimmten Kapitel
        chapter_number = args.from_chapter
        print(f"Lade Kapitel {chapter_number}...")
        get_all_chapters(chapter_number)

    else:
        print("Benutze -a, -n, -c <nummer> oder -s <nummer> um Kapitel zu laden.")


if __name__ == "__main__":
    main()
