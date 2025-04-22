import re
import requests
from PIL import Image
from io import BytesIO
import os
from playwright.sync_api import sync_playwright


BASE_URL = "https://onepiece.tube/manga/kapitel/"
START_CHAPTER = 420  # Start bei Kapitel 420
MAX_404S = 5  # Stoppt nach 5 aufeinanderfolgende 404-Fehler


def get_all_chapters(first_chapter=START_CHAPTER):
    """
    Holt alle Kapitel von OnePiece ab Kapitel 420 und listet sie auf.
    Stoppt, wenn 5 aufeinanderfolgende 404 Fehler auftreten.
    """
    chapter_list = []  # Liste für Kapitel-URLs
    consecutive_404s = 0  # Zähler für aufeinanderfolgende 404er Fehler

    for chapter_num in range(first_chapter, 10000):  # Bis zum "Ende" der Kapitel
        chapter_url = f"{BASE_URL}{chapter_num}/1"  # Erste Seite eines Kapitels

        # Anfrage an die Seite senden und HTML einlesen
        response = requests.get(chapter_url)
        if response.status_code == 404:
            consecutive_404s += 1
            print(f"Kapitel {chapter_num} nicht gefunden (404). Fehler {consecutive_404s}/{MAX_404S}")
            if consecutive_404s >= MAX_404S:
                print("5 aufeinanderfolgende 404 Fehler – Abbruch!")
                break  # Nach 5 aufeinanderfolgende 404 Fehler beenden
            continue

        # Fehlerzähler zurücksetzen, wenn kein 404
        consecutive_404s = 0
        print(chapter_url)
        # BeautifulSoup, um den HTML-Inhalt zu parsen
        img_urls = get_chapter_image_urls(chapter_num)  # Deine Funktion, die die Bilder lädt
        #chapter_list.append(imgs)  # Die Bilder zu der Liste hinzufügen
        save_chapter_as_pdf(chapter_num, img_urls)

    print("Alle gefundenen Kapitel wurden geladen.")
    return chapter_list


def file_exists(url):
    try:
        response = requests.head(url)
        return response.status_code == 200
    except:
        return False

def get_chapter_image_urls(chapter):
    base_url = f"https://onepiece.tube/manga/kapitel/{chapter}/1"

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        page.goto(base_url, timeout=10000)

        # Warte, bis Bild geladen wurde
        page.wait_for_selector('img.img-responsive', timeout=10000)

        # Seitenzahlen extrahieren aus aria-label
        page_labels = page.locator('ul.nav-pages li.page-item a[aria-label]').all()
        page_numbers = []

        for a in page_labels:
            label = a.get_attribute('aria-label')
            match = re.search(r"Page (\d+)", label)
            if match:
                page_numbers.append(int(match.group(1)))

        if not page_numbers:
            print("Keine Seitenzahlen gefunden.")
            return []

        max_page = max(page_numbers)

        # Erstes Bild holen, um Pfad zu erkennen
        first_img = page.locator('img.img-responsive').first
        first_img_src = first_img.get_attribute('src')
        base_img_url = "/".join(first_img_src.split("/")[:-1])  # alles bis zum letzten Slash

        # Alle Bild-URLs zusammensammeln, inkl. Doppelseiten
        img_urls = []
        i = 1
        while i <= max_page:
            url = f"{base_img_url}/{i:02d}.png"
            if file_exists(url):
                img_urls.append(url)
                i += 1
            else:
                double_url = f"{base_img_url}/{i:02d}-{i+1:02d}.png"
                if file_exists(double_url):
                    img_urls.append(double_url)
                    i += 2
                else:
                    print(f"Fehlende Seite: {i}, weder Einzel- noch Doppelseite gefunden.")
                    i += 1  # Optional, um durchzugehen trotz Error

        browser.close()
        return img_urls



def save_chapter_as_pdf(chapter_num, img_urls, out_dir="pdfs"):
    """
    Lädt alle Bilder eines Kapitels und speichert sie als PDF.
    """
    print(f"Erstelle PDF für Kapitel {chapter_num}...")

    images = []

    for url in img_urls:
        try:
            response = requests.get(url)
            img = Image.open(BytesIO(response.content)).convert("RGB")
            images.append(img)
        except Exception as e:
            print(f"Fehler beim Laden von {url}: {e}")

    if not images:
        print(f"Keine Bilder für Kapitel {chapter_num} geladen.")
        return

    os.makedirs(out_dir, exist_ok=True)
    pdf_path = os.path.join(out_dir, f"OnePiece_{chapter_num}.pdf")

    images[0].save(pdf_path, save_all=True, append_images=images[1:])
    print(f"Kapitel {chapter_num} gespeichert als PDF: {pdf_path}")