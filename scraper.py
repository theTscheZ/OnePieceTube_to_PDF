import re
import requests
from PIL import Image
from io import BytesIO
import os
from playwright.sync_api import sync_playwright

def get_latest_chapter_number():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        page.goto("https://onepiece.tube/manga/kapitel-mangaliste", timeout=15000)

        # Den dritten div mit der Klasse 'border-bottom' greifen
        border_divs = page.locator("div.border-bottom")
        target_div = border_divs.nth(2)  # 0-based Index → der dritte ist index 2

        # Den <p>-Inhalt extrahieren
        value = target_div.locator("p").first.text_content()

        browser.close()

        if value and value.strip().isdigit():
            return int(value.strip())
        return None

BASE_URL = "https://onepiece.tube/manga/kapitel/"
START_CHAPTER = 420  # Start bei Kapitel 420
MAX_CHAPTER = get_latest_chapter_number()
MAX_404 = 5  # Stoppt nach 5 aufeinanderfolgende 404-Fehler


def get_all_chapters(first_chapter=START_CHAPTER, last_chapter=MAX_CHAPTER):
    """
    Holt alle Kapitel von OnePiece ab Kapitel 420 und listet sie auf.
    Stoppt, wenn 5 aufeinanderfolgende 404 Fehler auftreten.
    """
    chapter_list = []  # Liste für Kapitel-URLs
    consecutive_404s = 0  # Zähler für aufeinanderfolgende 404er Fehler

    for chapter_num in range(first_chapter, last_chapter + 1):  # Bis zum "Ende" der Kapitel
        chapter_url = f"{BASE_URL}{chapter_num}/1"  # Erste Seite eines Kapitels

        # Anfrage an die Seite senden und HTML einlesen
        response = requests.get(chapter_url)
        if response.status_code == 404:
            consecutive_404s += 1
            print(f"Kapitel {chapter_num} nicht gefunden (404). Fehler {consecutive_404s}/{MAX_404}")
            if consecutive_404s >= MAX_404:
                print("5 aufeinanderfolgende 404 Fehler – Abbruch!")
                break  # Nach 5 aufeinanderfolgende 404 Fehler beenden
            continue

        # Fehlerzähler zurücksetzen, wenn kein 404
        consecutive_404s = 0
        print(chapter_url)
        # BeautifulSoup, um den HTML-Inhalt zu parsen
        img_urls = get_chapter_image_urls(chapter_num)  # Deine Funktion, die die Bilder lädt
        chapter_list.append(img_urls)  # Die Bilder zu der Liste hinzufügen
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
    if check_chapter_downloaded(chapter):
        print(f"Kapitel {chapter} bereits heruntergeladen.")
        return []

    base_url = f"https://onepiece.tube/manga/kapitel/{chapter}/1"

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        page.goto(base_url, timeout=10000)

        # Warte, bis das erste Bild geladen ist
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

        img_urls = []
        new_base_img_url = base_img_url
        # Sonderseiten
        i = 1
        while isSonderseite(new_base_img_url, first_img_src):
            img_urls.append(first_img_src.replace(" ", "%20"))
            # print(f"Sonderseite gefunden: {first_img_src}")
            # Hier kannst du die Logik für Sonderseiten hinzufügen
            i += 1
            next_url = f"https://onepiece.tube/manga/kapitel/{chapter}/{i}"
            # print(f"Next URL gefunden: {next_url}")
            page.goto(next_url, timeout=10000)
            page.wait_for_selector('img.img-responsive', timeout=10000)

            # Extrahiere den vollständigen src-Tag
            first_img_src = page.locator('img.img-responsive').first.get_attribute('src')
            new_base_img_url = "/".join(first_img_src.split("/")[:-1])
            if i > max_page:
                break

        if i <= max_page:
            # Alle Seiten bereits gefunden

            # Alle Bild-URLs zusammensammeln, inkl. Doppelseiten
            i = 1
            while i <= max_page:
                # Versuche, die Einzelbild-URL zu finden
                single = try_possible_urls(base_img_url, i)
                if single:
                    # print(f"SINGLE: {single}")
                    img_urls.append(single)
                    i += 1
                else:
                    # Falls Einzelbild nicht gefunden, versuche Doppelseiten
                    double = try_possible_double_urls(base_img_url, i)
                    if double:
                        # print(f"DOUBLE: {double}")
                        img_urls.append(double)
                        i += 2
                    else:
                        # Ich geh davon aus, dass die Seite existiert, also weiter
                        next_url = f"https://onepiece.tube/manga/kapitel/{chapter}/{i}"
                        page.goto(next_url, timeout=10000)
                        page.wait_for_selector('img.img-responsive', timeout=10000)

                        # Extrahiere den vollständigen src-Tag
                        first_img_src = page.locator('img.img-responsive').first.get_attribute('src')
                        # print(first_img_src)
                        if file_exists(first_img_src):
                            img_urls.append(first_img_src)
                        else:
                            print(f"Fehlende Seite: {i}, weder Einzel- noch Doppelseite gefunden.")
                        i += 1

        browser.close()
        return img_urls


def save_chapter_as_pdf(chapter_num, img_urls, out_dir="pdfs"):
    """
    Lädt alle Bilder eines Kapitels und speichert sie als PDF.
    """
    if check_chapter_downloaded(chapter_num):
        return

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


def check_chapter_downloaded(chapter_num, out_dir="pdfs"):
    """
    Überprüft, ob ein Kapitel bereits heruntergeladen wurde.
    """
    pdf_path = os.path.join(out_dir, f"OnePiece_{chapter_num}.pdf")
    return os.path.exists(pdf_path)


def try_possible_urls(base_img_url, i):
    formats = [
        f"{i:02d}.png", f"{i:03d}.png",
        f"{i:02d}.jpg", f"{i:03d}.jpg"
    ]
    for fmt in formats:
        url = f"{base_img_url}/{fmt}"
        if file_exists(url):
            return url
    return None


def try_possible_double_urls(base_img_url, i):
    formats = [
        f"{i:02d}-{i + 1:02d}.png", f"{i:03d}-{i + 1:03d}.png",
        f"{i:02d}-{i + 1:02d}.jpg", f"{i:03d}-{i + 1:03d}.jpg"
    ]
    for fmt in formats:
        url = f"{base_img_url}/{fmt}"
        if file_exists(url):
            return url
    return None


def isSonderseite(base_url, img_src):
    # Leerzeichen entfernen
    img_src = img_src.replace(" ", "%20")
    #print(f"Prüfe Bildquelle: {img_src}")

    # Prüfen, ob eine Einzelbild-URL existiert
    sonderseite = try_possible_urls(base_url, 1)
    #print(f"Einzelbild-URL gefunden: {sonderseite}")

    # Wenn die URL gefunden wurde und sie mit img_src übereinstimmt, handelt es sich nicht um eine Sonderseite
    if sonderseite == img_src:
        return False  # Keine Sonderseite, weil die reguläre Seite gefunden wurde

    # Prüfen, ob eine Doppelseiten-URL existiert
    sonderseite = try_possible_double_urls(base_url, 1)
    #print(f"Doppelseiten-URL gefunden: {sonderseite}")

    # Wenn die URL gefunden wurde und sie mit img_src übereinstimmt, handelt es sich nicht um eine Sonderseite
    if sonderseite == img_src:
        return False  # Keine Sonderseite, weil die reguläre Doppelseite gefunden wurde

    # Wenn weder eine Einzel- noch Doppelseiten-URL gefunden wurde, ist es eine Sonderseite
    return True