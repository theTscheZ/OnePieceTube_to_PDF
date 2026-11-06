import XCTest
@testable import OnePieceTubeReader

final class ChapterRulesTests: XCTestCase {
    func testNaturalChapterOrderAndCopySuffix() {
        XCTAssertEqual(["OnePiece_10.pdf", "OnePiece_9.pdf"].sorted(by: ChapterRules.naturalOrder), ["OnePiece_9.pdf", "OnePiece_10.pdf"])
        XCTAssertEqual(ChapterRules.number("OnePiece_500 (2).pdf"), 500)
        XCTAssertEqual(ChapterRules.number("Kapitel 501.pdf"), 501)
        XCTAssertNil(ChapterRules.number("Notizen.pdf"))
    }
    func testDownloadDefaultsAndAutomaticCatchUp() throws {
        XCTAssertEqual(ChapterRules.nextChapter([]), 420)
        XCTAssertEqual(ChapterRules.nextChapter(["OnePiece_500.pdf"]), 501)
        XCTAssertEqual(try ChapterRules.automaticRange(latest: 503, names: [], downloadedThrough: 0), 503...503)
        XCTAssertEqual(try ChapterRules.automaticRange(latest: 503, names: ["OnePiece_500.pdf"], downloadedThrough: 0), 501...503)
    }
    func testFinishedDeletedChaptersAndFailedChapterRetry() throws {
        XCTAssertNil(try ChapterRules.automaticRange(latest: 503, names: [], downloadedThrough: 503))
        XCTAssertEqual(try ChapterRules.automaticRange(latest: 503, names: ["OnePiece_501.pdf"], downloadedThrough: 501), 502...503)
        XCTAssertNil(try ChapterRules.automaticRange(latest: 500, names: ["OnePiece_501.pdf"], downloadedThrough: 0))
        XCTAssertThrowsError(try ChapterRules.automaticRange(latest: 0, names: [], downloadedThrough: 0))
    }
    func testNamesCannotEscapeTheLibrary() throws {
        XCTAssertEqual(try ChapterRules.safeName("../../kapitel.PDF"), ".._.._kapitel.pdf")
        XCTAssertThrowsError(try ChapterRules.safeName(".."))
        XCTAssertThrowsError(try ChapterRules.safeName("  "))
    }
    func testReaderCrossesChapterBoundariesAndSkipsBrokenPDFs() {
        let counts = [2, 0, 3]
        XCTAssertEqual(MangaPosition(chapter: 0, page: 1).moving(forward: true, counts: counts), MangaPosition(chapter: 2, page: 0))
        XCTAssertEqual(MangaPosition(chapter: 2, page: 0).moving(forward: false, counts: counts), MangaPosition(chapter: 0, page: 1))
        XCTAssertNil(MangaPosition(chapter: 0, page: 0).moving(forward: false, counts: counts))
        XCTAssertNil(MangaPosition(chapter: 2, page: 2).moving(forward: true, counts: counts))
    }
    func testReaderMovesWithinAPDF() {
        XCTAssertEqual(MangaPosition(chapter: 0, page: 0).moving(forward: true, counts: [3]), MangaPosition(chapter: 0, page: 1))
        XCTAssertNil(MangaPosition(chapter: 9, page: 0).moving(forward: true, counts: [3]))
    }
    func testGallerySweepShrinksBackToBaseline() {
        let sweep = GallerySweep(names: ["a", "b", "c", "d"], baseline: ["a"], anchor: 1)
        XCTAssertEqual(sweep.selection(at: 3), ["a", "b", "c", "d"])
        XCTAssertEqual(sweep.selection(at: 1), ["a", "b"])
    }
    func testGallerySweepCanDeselectAndAutoScrollAccelerates() {
        let sweep = GallerySweep(names: ["a", "b", "c"], baseline: ["a", "b", "c"], anchor: 1)
        XCTAssertEqual(sweep.selection(at: 2), ["a"])
        XCTAssertEqual(sweep.selection(at: 1), ["a", "c"])
        XCTAssertEqual(GallerySweep.velocity(y: 200, height: 400), 0)
        XCTAssertLessThan(GallerySweep.velocity(y: 0, height: 400), GallerySweep.velocity(y: 40, height: 400))
        XCTAssertGreaterThan(GallerySweep.velocity(y: 400, height: 400), GallerySweep.velocity(y: 360, height: 400))
    }
}

final class ChapterSourceTests: XCTestCase {
    private let html = #"<script>window.__data = {"categoryEntries":[{"number":500},{"number":503}],"chapter":{"pages":[{"url":"https://example.org/1.jpg"},{"url":"https://example.org/double page.jpg"}]}};</script>"#
    func testEmbeddedMetadataAndPageOrder() throws {
        XCTAssertEqual(try ChapterSource.latest(html), 503)
        XCTAssertEqual(try ChapterSource.embeddedImages(html)?.map(\.absoluteString), ["https://example.org/1.jpg", "https://example.org/double%20page.jpg"])
    }
    func testWhitespaceInJSONAssignment() throws {
        XCTAssertEqual(try ChapterSource.latest(html.replacingOccurrences(of: "window.__data =", with: "window.__data\n=\n")), 503)
    }
    func testLegacyPagesAndRelativeImageURL() throws {
        let legacy = #"<a href='/manga/kapitel/504/1'>504</a><a aria-label='Page 17'>17</a><img src='/test.jpg' class='img-responsive'>"#
        XCTAssertEqual(try ChapterSource.latest(legacy), 504)
        XCTAssertEqual(ChapterSource.pageCount(legacy), 17)
        XCTAssertEqual(try ChapterSource.legacyImage(legacy, relativeTo: ChapterSource.base).absoluteString, "https://onepiece.tube/test.jpg")
    }
    func testErrorsDoNotGuessImagesOrChapterNumbers() {
        XCTAssertThrowsError(try ChapterSource.latest("<p>Offline</p>"))
        XCTAssertThrowsError(try ChapterSource.embeddedImages(html.replacingOccurrences(of: "https://", with: "http://")))
        XCTAssertThrowsError(try ChapterSource.legacyImage("<p>Kein Bild</p>", relativeTo: ChapterSource.base))
    }
}
