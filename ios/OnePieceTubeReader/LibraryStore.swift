import Foundation
import PDFKit
import SwiftUI

enum ReaderEnvironment {
    static var isUITesting: Bool {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("--ui-testing")
        #else
        false
        #endif
    }
    static let defaults: UserDefaults = isUITesting ? UserDefaults(suiteName: "at.aaron.onepiece.ios.uitests")! : .standard
}

enum ReadStatus: String, Codable, CaseIterable, Identifiable {
    case unread, started, read
    var id: String { rawValue }
    var title: String { switch self { case .unread: "Ungelesen"; case .started: "Angefangen"; case .read: "Gelesen" } }
}
struct ReadingRecord: Codable, Equatable { var page = 0; var status: ReadStatus = .unread }
struct Chapter: Identifiable, Hashable {
    var id: String { name }
    let name: String
    let bytes: Int64
    let modified: Date
    var title: String { (name as NSString).deletingPathExtension }
    var number: Int? { ChapterRules.number(name) }
}

@MainActor final class LibraryStore: ObservableObject {
    static let shared = LibraryStore()
    @Published private(set) var chapters: [Chapter] = []
    @Published private(set) var records: [String: ReadingRecord] = [:]
    @Published private(set) var lastName: String?
    @Published var message: String?
    let directory: URL
    private let defaults: UserDefaults

    init(directory: URL? = nil, defaults: UserDefaults = ReaderEnvironment.defaults) {
        self.defaults = defaults
        self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent(ReaderEnvironment.isUITesting ? "UITestChapters" : "chapters", isDirectory: true)
        do { try FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true) }
        catch { message = error.localizedDescription }
        if let data = defaults.data(forKey: "reading") { records = (try? JSONDecoder().decode([String: ReadingRecord].self, from: data)) ?? [:] }
        lastName = defaults.string(forKey: "lastChapter")
        refresh()
    }
    func url(_ name: String) -> URL { directory.appendingPathComponent(name) }
    func record(_ name: String) -> ReadingRecord { records[name] ?? ReadingRecord() }
    func refresh() {
        do {
            let keys: Set<URLResourceKey> = [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]
            chapters = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: Array(keys), options: [.skipsHiddenFiles])
                .filter { $0.pathExtension.lowercased() == "pdf" }.compactMap { file in
                    guard let values = try? file.resourceValues(forKeys: keys), values.isRegularFile == true else { return nil }
                    return Chapter(name: file.lastPathComponent, bytes: Int64(values.fileSize ?? 0), modified: values.contentModificationDate ?? .distantPast)
                }.sorted { ChapterRules.naturalOrder($0.name, $1.name) }
        } catch { message = error.localizedDescription }
    }
    private func persist() {
        if let data = try? JSONEncoder().encode(records) { defaults.set(data, forKey: "reading") }
        defaults.set(lastName, forKey: "lastChapter")
    }
    func savePosition(name: String, page: Int, total: Int) {
        var record = record(name)
        record.page = max(0, page)
        if record.status != .read { record.status = total > 0 && page == total - 1 ? .read : .started }
        records[name] = record; lastName = name; persist()
    }
    func mark(_ names: Set<String>, as status: ReadStatus) {
        for name in names {
            var entry = record(name); entry.status = status
            if status == .unread { entry.page = 0; if lastName == name { lastName = nil } }
            records[name] = entry
        }
        persist()
    }
    func rename(_ name: String, to proposed: String) throws {
        let safe = try ChapterRules.safeName(proposed)
        if safe == name { return }
        guard !FileManager.default.fileExists(atPath: url(safe).path) else { throw ReaderError.nameExists }
        try FileManager.default.moveItem(at: url(name), to: url(safe))
        records[safe] = records.removeValue(forKey: name)
        if lastName == name { lastName = safe }; persist(); refresh()
    }
    @discardableResult func delete(_ names: Set<String>) -> Set<String> {
        var failures: Set<String> = []
        for name in names where chapters.contains(where: { $0.name == name }) {
            do {
                try FileManager.default.removeItem(at: url(name)); records.removeValue(forKey: name)
                if lastName == name { lastName = nil }
            } catch { failures.insert(name) }
        }
        persist(); refresh()
        message = failures.isEmpty ? "\(names.count) Kapitel gelöscht" : "\(failures.count) Kapitel konnten nicht gelöscht werden"
        return failures
    }
    func importPDFs(_ urls: [URL]) async {
        let destination = directory
        let outcome = await Task.detached(priority: .userInitiated) { () -> (Int, String?) in
            var added = 0; var failure: String?
            for source in urls {
                let access = source.startAccessingSecurityScopedResource()
                defer { if access { source.stopAccessingSecurityScopedResource() } }
                let temporary = destination.appendingPathComponent(".\(UUID().uuidString).part")
                defer { try? FileManager.default.removeItem(at: temporary) }
                do {
                    try FileManager.default.copyItem(at: source, to: temporary)
                    guard let pdf = PDFDocument(url: temporary), !pdf.isLocked, pdf.pageCount > 0 else { throw ReaderError.invalidPDF }
                    let base = try ChapterRules.safeName(source.lastPathComponent)
                    var target = destination.appendingPathComponent(base); var index = 2
                    while FileManager.default.fileExists(atPath: target.path) {
                        target = destination.appendingPathComponent("\((base as NSString).deletingPathExtension) (\(index)).pdf"); index += 1
                    }
                    try FileManager.default.moveItem(at: temporary, to: target); added += 1
                } catch { failure = error.localizedDescription }
            }
            return (added, failure)
        }.value
        refresh(); message = outcome.1.map { "\(outcome.0) importiert · \($0)" } ?? "\(outcome.0) PDFs importiert"
    }
}

enum Accent: String, CaseIterable, Identifiable {
    case mint, blue, lavender, pink, peach, gold
    var id: String { rawValue }
    var title: String { switch self { case .mint: "Mint"; case .blue: "Blau"; case .lavender: "Lavendel"; case .pink: "Rosa"; case .peach: "Pfirsich"; case .gold: "Gold" } }
    var color: Color {
        let value: UInt32 = switch self { case .mint: 0x77DBC8; case .blue: 0xA6C8FF; case .lavender: 0xD0BCFF; case .pink: 0xFFB1C8; case .peach: 0xFFB68C; case .gold: 0xEAC970 }
        return Color(red: Double((value >> 16) & 255) / 255, green: Double((value >> 8) & 255) / 255, blue: Double(value & 255) / 255)
    }
}

enum LayoutSpace {
    static let small: CGFloat = 8
    static let medium: CGFloat = 16
    static let large: CGFloat = 24
}

@MainActor final class ReaderSettings: ObservableObject {
    static let shared = ReaderSettings()
    private let defaults: UserDefaults
    @Published var keepCurrent: Bool { didSet { defaults.set(keepCurrent, forKey: "keepCurrent") } }
    @Published var wifiOnly: Bool { didSet { defaults.set(wifiOnly, forKey: "wifiOnly") } }
    @Published var accent: Accent { didSet { defaults.set(accent.rawValue, forKey: "accent") } }
    var downloadedThrough: Int { defaults.integer(forKey: "downloadedThrough") }
    var lastAutomaticCheck: Date {
        get { defaults.object(forKey: "lastAutomaticCheck") as? Date ?? .distantPast }
        set { defaults.set(newValue, forKey: "lastAutomaticCheck") }
    }
    func rememberDownloaded(_ chapter: Int) { if chapter > downloadedThrough { defaults.set(chapter, forKey: "downloadedThrough") } }
    init(defaults: UserDefaults = ReaderEnvironment.defaults) {
        self.defaults = defaults
        keepCurrent = defaults.bool(forKey: "keepCurrent")
        wifiOnly = defaults.object(forKey: "wifiOnly") as? Bool ?? true
        accent = Accent(rawValue: defaults.string(forKey: "accent") ?? "") ?? .mint
    }
}
