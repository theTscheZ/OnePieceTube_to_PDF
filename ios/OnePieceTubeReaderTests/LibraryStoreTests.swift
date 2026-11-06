import XCTest
import PDFKit
import UIKit
@testable import OnePieceTubeReader

@MainActor final class LibraryStoreTests: XCTestCase {
    private func fixture() throws -> (LibraryStore, UserDefaults, URL) {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let defaults = UserDefaults(suiteName: UUID().uuidString)!
        return (LibraryStore(directory: directory, defaults: defaults), defaults, directory)
    }
    private func pdf(_ url: URL, pages: Int = 2) throws {
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 300, height: 450))
        try renderer.writePDF(to: url) { context in
            for _ in 0..<pages { context.beginPage(); UIColor.white.setFill(); context.cgContext.fill(CGRect(x: 0, y: 0, width: 300, height: 450)) }
        }
    }
    func testReadingStatusPersistsAndUnreadResetsProgress() throws {
        let (store, defaults, directory) = try fixture(); defer { try? FileManager.default.removeItem(at: directory) }
        let name = "OnePiece_500.pdf"; try pdf(store.url(name)); store.refresh()
        XCTAssertEqual(store.record(name).status, .unread)
        store.savePosition(name: name, page: 0, total: 2); XCTAssertEqual(store.record(name).status, .started)
        store.savePosition(name: name, page: 1, total: 2); XCTAssertEqual(store.record(name).status, .read)
        let restored = LibraryStore(directory: directory, defaults: defaults)
        XCTAssertEqual(restored.record(name).page, 1); XCTAssertEqual(restored.record(name).status, .read)
        restored.mark([name], as: .unread); XCTAssertEqual(restored.record(name).page, 0); XCTAssertNil(restored.lastName)
    }
    func testRenameAndDeleteCarryAndRemoveState() throws {
        let (store, _, directory) = try fixture(); defer { try? FileManager.default.removeItem(at: directory) }
        let name = "OnePiece_500.pdf"; try pdf(store.url(name)); store.refresh()
        store.savePosition(name: name, page: 1, total: 2)
        try store.rename(name, to: "Kapitel 500")
        XCTAssertEqual(store.record("Kapitel 500.pdf").status, .read)
        XCTAssertEqual(store.lastName, "Kapitel 500.pdf")
        XCTAssertTrue(store.delete(["Kapitel 500.pdf"]).isEmpty)
        XCTAssertTrue(store.chapters.isEmpty); XCTAssertNil(store.lastName)
    }
    func testImportCreatesCopiesAndRejectsBrokenPDFs() async throws {
        let (store, _, directory) = try fixture(); defer { try? FileManager.default.removeItem(at: directory) }
        let sourceDirectory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: sourceDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: sourceDirectory) }
        let source = sourceDirectory.appendingPathComponent("OnePiece_500.pdf"); try pdf(source)
        await store.importPDFs([source]); await store.importPDFs([source])
        XCTAssertEqual(store.chapters.count, 2)
        XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
        let broken = sourceDirectory.appendingPathComponent("broken.pdf"); try Data("not a PDF".utf8).write(to: broken)
        await store.importPDFs([broken]); XCTAssertEqual(store.chapters.count, 2)
        store.delete(Set(store.chapters.map(\.name)))
        XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
    }
    func testSettingsPersistAndWatermarkCannotMoveBackwards() {
        let defaults = UserDefaults(suiteName: UUID().uuidString)!
        let settings = ReaderSettings(defaults: defaults)
        XCTAssertFalse(settings.keepCurrent); XCTAssertTrue(settings.wifiOnly)
        settings.keepCurrent = true; settings.accent = .lavender; settings.rememberDownloaded(503); settings.rememberDownloaded(500)
        let restored = ReaderSettings(defaults: defaults)
        XCTAssertTrue(restored.keepCurrent); XCTAssertEqual(restored.accent, .lavender); XCTAssertEqual(restored.downloadedThrough, 503)
    }
}
