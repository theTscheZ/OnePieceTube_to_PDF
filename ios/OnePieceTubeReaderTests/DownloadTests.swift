import XCTest
import UIKit
import PDFKit
@testable import OnePieceTubeReader

private final class FixtureURLProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var fixture: [String: (Int, Data)] = [:]
    static func configure(_ responses: [String: (Int, Data)]) { lock.lock(); defer { lock.unlock() }; fixture = responses }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock(); let result = Self.fixture[request.url!.absoluteString]; Self.lock.unlock()
        guard let (status, data) = result else { client?.urlProtocol(self, didFailWithError: URLError(.resourceUnavailable)); return }
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Length": "\(data.count)"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data); client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}

@MainActor final class DownloadTests: XCTestCase {
    private let chapterHTML = #"<script>window.__data = {"chapter":{"pages":[{"url":"https://example.org/1.jpg"},{"url":"https://example.org/2.jpg"}]}};</script>"#
    private func directory() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true); return url
    }
    private func image() -> Data {
        UIGraphicsImageRenderer(size: CGSize(width: 20, height: 30)).jpegData(withCompressionQuality: 0.9) { context in
            UIColor.white.setFill(); context.fill(CGRect(x: 0, y: 0, width: 20, height: 30))
        }
    }
    func testDownloadsOrderedImagesAndPublishesValidPDF() async throws {
        let folder = try directory(); defer { try? FileManager.default.removeItem(at: folder) }
        FixtureURLProtocol.configure([ChapterSource.chapterURL(500).absoluteString: (200, Data(chapterHTML.utf8)),
            "https://example.org/1.jpg": (200, image()), "https://example.org/2.jpg": (200, image())])
        let service = ChapterDownloader(protocolClasses: [FixtureURLProtocol.self])
        let saved = try await service.download(500, directory: folder, wifiOnly: false) { _ in }
        XCTAssertTrue(saved)
        let target = folder.appendingPathComponent("OnePiece_500.pdf")
        XCTAssertEqual(PDFDocument(url: target)?.pageCount, 2)
        let skipped = try await service.download(500, directory: folder, wifiOnly: false) { _ in }
        XCTAssertFalse(skipped)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: folder.path), ["OnePiece_500.pdf"])
    }
    func testBrokenImageNeverPublishesAnIncompletePDF() async throws {
        let folder = try directory(); defer { try? FileManager.default.removeItem(at: folder) }
        FixtureURLProtocol.configure([ChapterSource.chapterURL(500).absoluteString: (200, Data(chapterHTML.utf8)),
            "https://example.org/1.jpg": (200, image()), "https://example.org/2.jpg": (200, Data("broken image".utf8))])
        let service = ChapterDownloader(protocolClasses: [FixtureURLProtocol.self])
        do { _ = try await service.download(500, directory: folder, wifiOnly: false) { _ in }; XCTFail("Broken page must fail") } catch { }
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: folder.path).isEmpty)
    }
    func testHTTPErrorIsReported() async throws {
        FixtureURLProtocol.configure([ChapterSource.chapterURL(420).absoluteString: (503, Data())])
        let service = ChapterDownloader(protocolClasses: [FixtureURLProtocol.self])
        do { _ = try await service.latest(wifiOnly: false); XCTFail("HTTP error must fail") }
        catch ReaderError.http(let code) { XCTAssertEqual(code, 503) }
        catch { XCTFail("Unexpected error: \(error)") }
    }
}
