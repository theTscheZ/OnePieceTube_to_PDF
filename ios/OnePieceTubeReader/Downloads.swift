import Foundation
import UIKit
import PDFKit
import ImageIO
import UserNotifications

enum DownloadMode: String, CaseIterable, Identifiable {
    case latest, single, range, from, all
    var id: String { rawValue }
    var title: String { switch self { case .latest: "Neuestes"; case .single: "Ein Kapitel"; case .range: "Bereich"; case .from: "Ab Kapitel"; case .all: "Alle ab 420" } }
}

actor ChapterDownloader {
    private let protocolClasses: [AnyClass]?
    init(protocolClasses: [AnyClass]? = nil) { self.protocolClasses = protocolClasses }
    private func session(wifiOnly: Bool) -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        if let protocolClasses { configuration.protocolClasses = protocolClasses }
        configuration.timeoutIntervalForRequest = 45
        configuration.timeoutIntervalForResource = 180
        configuration.allowsCellularAccess = !wifiOnly
        configuration.allowsExpensiveNetworkAccess = !wifiOnly
        configuration.allowsConstrainedNetworkAccess = !wifiOnly
        return URLSession(configuration: configuration)
    }
    private func request(_ url: URL) -> URLRequest {
        var request = URLRequest(url: url)
        request.setValue("Mozilla/5.0 (iPhone) OnePieceTubeReader/1.0", forHTTPHeaderField: "User-Agent")
        request.setValue(ChapterSource.base.absoluteString + "/", forHTTPHeaderField: "Referer")
        return request
    }
    private func html(_ url: URL, session: URLSession) async throws -> String {
        let (data, response) = try await session.data(for: request(url))
        guard let response = response as? HTTPURLResponse else { throw ReaderError.noChapter }
        guard (200..<300).contains(response.statusCode) else { throw ReaderError.http(response.statusCode) }
        guard data.count <= 5 * 1024 * 1024 else { throw ReaderError.oversized }
        guard let text = String(data: data, encoding: .utf8) else { throw ReaderError.noChapter }
        return text
    }
    func latest(wifiOnly: Bool) async throws -> Int {
        let connection = session(wifiOnly: wifiOnly); defer { connection.invalidateAndCancel() }
        return try ChapterSource.latest(await html(ChapterSource.chapterURL(420), session: connection))
    }
    /// Stages images on disk, renders one image at a time, then atomically publishes a checked PDF.
    func download(_ chapter: Int, directory: URL, wifiOnly: Bool,
                  progress: @escaping @Sendable (String) async -> Void) async throws -> Bool {
        let target = directory.appendingPathComponent("OnePiece_\(chapter).pdf")
        if FileManager.default.fileExists(atPath: target.path) { return false }
        let connection = session(wifiOnly: wifiOnly); defer { connection.invalidateAndCancel() }
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let temporary = directory.appendingPathComponent(".\(UUID().uuidString).part")
        defer { try? FileManager.default.removeItem(at: folder); try? FileManager.default.removeItem(at: temporary) }
        let firstURL = ChapterSource.chapterURL(chapter)
        let firstHTML = try await html(firstURL, session: connection)
        var images = try ChapterSource.embeddedImages(firstHTML) ?? []
        if images.isEmpty {
            for page in 1...ChapterSource.pageCount(firstHTML) {
                try Task.checkCancellation()
                let pageURL = ChapterSource.chapterURL(chapter, page: page)
                let text: String
                if page == 1 { text = firstHTML } else { text = try await html(pageURL, session: connection) }
                images.append(try ChapterSource.legacyImage(text, relativeTo: pageURL))
            }
        }
        var localImages: [URL] = []
        for (index, url) in images.enumerated() {
            try Task.checkCancellation()
            await progress("Kapitel \(chapter) · Seite \(index + 1)/\(images.count)")
            let (source, response) = try await connection.download(for: request(url))
            defer { try? FileManager.default.removeItem(at: source) }
            guard let response = response as? HTTPURLResponse, (200..<300).contains(response.statusCode) else { throw ReaderError.invalidImage }
            let bytes = (try source.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0
            guard bytes > 0, bytes <= 40 * 1024 * 1024 else { throw ReaderError.oversized }
            let destination = folder.appendingPathComponent("\(index).jpg")
            try autoreleasepool {
                guard let imageSource = CGImageSourceCreateWithURL(source as CFURL, nil),
                      let properties = CGImageSourceCopyPropertiesAtIndex(imageSource, 0, nil) as? [CFString: Any],
                      let width = properties[kCGImagePropertyPixelWidth] as? NSNumber,
                      let height = properties[kCGImagePropertyPixelHeight] as? NSNumber,
                      width.doubleValue > 0, height.doubleValue > 0 else { throw ReaderError.invalidImage }
                let reduction = min(1, sqrt(12_000_000 / (width.doubleValue * height.doubleValue)))
                let maximum = min(4096, max(width.doubleValue, height.doubleValue) * reduction)
                let options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceThumbnailMaxPixelSize: max(1, Int(maximum)), kCGImageSourceCreateThumbnailWithTransform: true]
                guard let cgImage = CGImageSourceCreateThumbnailAtIndex(imageSource, 0, options as CFDictionary),
                      let data = UIImage(cgImage: cgImage).jpegData(compressionQuality: 0.95) else { throw ReaderError.invalidImage }
                try data.write(to: destination)
            }
            localImages.append(destination)
        }
        await progress("Kapitel \(chapter) wird als PDF gespeichert …")
        var renderingError: Error?
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 595, height: 842))
        try renderer.writePDF(to: temporary) { context in
            for url in localImages {
                if Task.isCancelled { renderingError = CancellationError(); break }
                autoreleasepool {
                    guard let image = UIImage(contentsOfFile: url.path), image.size.width > 0, image.size.height > 0 else { renderingError = ReaderError.invalidImage; return }
                    let box = CGRect(origin: .zero, size: image.size)
                    context.beginPage(withBounds: box, pageInfo: [:])
                    UIColor.white.setFill(); context.cgContext.fill(box); image.draw(in: box)
                }
                if renderingError != nil { break }
            }
        }
        if let renderingError { throw renderingError }
        try Task.checkCancellation()
        guard let pdf = PDFDocument(url: temporary), pdf.pageCount == images.count, !pdf.isLocked else { throw ReaderError.invalidPDF }
        if FileManager.default.fileExists(atPath: target.path) { return false }
        try FileManager.default.moveItem(at: temporary, to: target)
        return true
    }
}

@MainActor final class DownloadModel: ObservableObject {
    static let shared = DownloadModel()
    @Published private(set) var busy = false
    @Published private(set) var automatic = false
    @Published private(set) var progress = ""
    private let service = ChapterDownloader()
    private var job: Task<Bool, Never>?
    func cancel() { job?.cancel() }
    func cancelAutomatic() { if automatic { cancel() } }

    func start(mode: DownloadMode, first: Int, last: Int) {
        guard !busy else { return }
        busy = true; automatic = false; progress = "Kapitel werden ermittelt …"
        job = Task { [self] in
            defer { busy = false; job = nil }
            do {
                let latest: Int
                if mode == .single || mode == .range { latest = first } else { latest = try await service.latest(wifiOnly: false) }
                let start = mode == .latest ? latest : mode == .all ? 420 : first
                let end = mode == .single ? first : mode == .range ? last : latest
                guard (1...100000).contains(start), (start...100000).contains(end) else { throw ReaderError.invalidRange }
                let count = try await run(start...end, automatic: false, wifiOnly: false)
                LibraryStore.shared.message = "\(count) neue Kapitel geladen"
                return true
            } catch is CancellationError { LibraryStore.shared.message = "Download abgebrochen"; return false
            } catch { LibraryStore.shared.message = error.localizedDescription; return false }
        }
    }
    @discardableResult func checkAutomatically() async -> Bool {
        let settings = ReaderSettings.shared
        guard settings.keepCurrent, !busy else { return false }
        busy = true; automatic = true; progress = "Neue Kapitel werden geprüft …"
        let task = Task { [self] () -> Bool in
            defer { busy = false; automatic = false; job = nil }
            do {
                let latest = try await service.latest(wifiOnly: settings.wifiOnly)
                guard let range = try ChapterRules.automaticRange(latest: latest, names: LibraryStore.shared.chapters.map(\.name), downloadedThrough: settings.downloadedThrough) else { return true }
                _ = try await run(range, automatic: true, wifiOnly: settings.wifiOnly)
                return true
            } catch { return false } // Scheduled again later; finished chapters stay available.
        }
        job = task
        return await withTaskCancellationHandler(operation: { await task.value }, onCancel: { task.cancel() })
    }
    private func run(_ chapters: ClosedRange<Int>, automatic: Bool, wifiOnly: Bool) async throws -> Int {
        let library = LibraryStore.shared; let settings = ReaderSettings.shared
        var count = 0
        for chapter in chapters {
            try Task.checkCancellation()
            if automatic && !settings.keepCurrent { throw CancellationError() }
            if automatic && (chapter <= settings.downloadedThrough || library.chapters.contains(where: { $0.number == chapter })) {
                settings.rememberDownloaded(chapter); continue
            }
            let saved = try await service.download(chapter, directory: library.directory, wifiOnly: wifiOnly) { [weak self] text in
                await MainActor.run { self?.progress = text }
            }
            library.refresh()
            if saved { count += 1 }
            if automatic {
                settings.rememberDownloaded(chapter)
                if saved && settings.keepCurrent { await Self.notify(chapter) }
            }
        }
        return count
    }
    private static func notify(_ chapter: Int) async {
        let center = UNUserNotificationCenter.current()
        let authorization = await center.notificationSettings()
        guard [.authorized, .provisional, .ephemeral].contains(authorization.authorizationStatus) else { return }
        let content = UNMutableNotificationContent()
        content.title = "Neues Kapitel \(chapter)"; content.body = "Heruntergeladen · Jetzt lesen"
        content.userInfo = ["chapter": "OnePiece_\(chapter).pdf"]
        content.sound = .default
        try? await center.add(UNNotificationRequest(identifier: "chapter-\(chapter)", content: content, trigger: nil))
    }
}
