import Foundation

enum ReaderError: LocalizedError {
    case invalidPDF, invalidName, nameExists, noChapter, noPages, invalidImage, invalidRange, http(Int), oversized
    var errorDescription: String? {
        switch self {
        case .invalidPDF: return "Die PDF ist beschädigt, leer oder passwortgeschützt."
        case .invalidName: return "Bitte einen gültigen Dateinamen eingeben."
        case .nameExists: return "Dieser Dateiname ist bereits vergeben."
        case .noChapter: return "Keine Kapitel gefunden. Die Website hat sich möglicherweise geändert."
        case .noPages: return "Keine Manga-Seiten gefunden."
        case .invalidImage: return "Eine Manga-Seite konnte nicht geladen werden."
        case .invalidRange: return "Bitte einen gültigen Kapitelbereich eingeben."
        case .http(let code): return "Die Website antwortet mit HTTP \(code)."
        case .oversized: return "Die Datei ist zu groß."
        }
    }
}

enum ChapterRules {
    static func number(_ name: String) -> Int? {
        let pattern = #"(?i)^(?:(?:one[ _-]*piece)[ _-]*(?:(?:kapitel|chapter)[ _-]*)?|(?:kapitel|chapter)[ _-]*)?(\d+)(?:\D|$)"#
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: name, range: NSRange(name.startIndex..., in: name)),
              let range = Range(match.range(at: 1), in: name),
              let number = Int(name[range]), (1...100000).contains(number) else { return nil }
        return number
    }
    static func naturalOrder(_ left: String, _ right: String) -> Bool {
        let order = left.compare(right, options: [.numeric, .caseInsensitive], locale: Locale(identifier: "en_US_POSIX"))
        return order == .orderedSame ? left < right : order == .orderedAscending
    }
    static func nextChapter(_ names: [String]) -> Int { (names.compactMap(number).max().map { min($0 + 1, 100000) }) ?? 420 }
    static func automaticRange(latest: Int, names: [String], downloadedThrough: Int) throws -> ClosedRange<Int>? {
        guard (1...100000).contains(latest) else { throw ReaderError.noChapter }
        let highest = max(names.compactMap(number).max() ?? 0, downloadedThrough)
        let first = highest == 0 ? latest : highest + 1
        return first <= latest ? first...latest : nil
    }
    static func safeName(_ name: String) throws -> String {
        var clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.lowercased().hasSuffix(".pdf") { clean = String(clean.dropLast(4)) }
        clean = clean.components(separatedBy: CharacterSet(charactersIn: "/\\:").union(.controlCharacters)).joined(separator: "_")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty, clean != ".", clean != ".." else { throw ReaderError.invalidName }
        return String(clean.prefix(160)) + ".pdf"
    }
}

struct MangaPosition: Equatable {
    var chapter: Int
    var page: Int
    func moving(forward: Bool, counts: [Int]) -> MangaPosition? {
        guard counts.indices.contains(chapter), counts[chapter] > 0, (0..<counts[chapter]).contains(page) else { return nil }
        if forward {
            if page + 1 < counts[chapter] { return .init(chapter: chapter, page: page + 1) }
            if let next = counts.indices.dropFirst(chapter + 1).first(where: { counts[$0] > 0 }) { return .init(chapter: next, page: 0) }
        } else {
            if page > 0 { return .init(chapter: chapter, page: page - 1) }
            if let previous = counts.indices.prefix(chapter).last(where: { counts[$0] > 0 }) { return .init(chapter: previous, page: counts[previous] - 1) }
        }
        return nil
    }
}

enum ChapterSource {
    static let base = URL(string: "https://onepiece.tube")!
    static func chapterURL(_ chapter: Int, page: Int = 1) -> URL {
        base.appendingPathComponent("manga/kapitel/\(chapter)/\(page)")
    }
    private static func capture(_ pattern: String, in text: String) -> [String] {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]) else { return [] }
        return regex.matches(in: text, range: NSRange(text.startIndex..., in: text)).compactMap {
            guard let range = Range($0.range(at: 1), in: text) else { return nil }; return String(text[range])
        }
    }
    private static func data(_ html: String) throws -> [String: Any]? {
        guard let json = capture(#"window\.__data\s*=\s*(\{.*?\})\s*;?\s*</script>"#, in: html).first else { return nil }
        return try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any]
    }
    static func latest(_ html: String) throws -> Int {
        if let entries = try data(html)?["categoryEntries"] as? [[String: Any]],
           let number = entries.compactMap({ ($0["number"] as? NSNumber)?.intValue }).max(), (1...100000).contains(number) { return number }
        guard let number = capture(#"/manga/kapitel/(\d+)"#, in: html).compactMap(Int.init).filter({ (1...100000).contains($0) }).max() else { throw ReaderError.noChapter }
        return number
    }
    static func embeddedImages(_ html: String) throws -> [URL]? {
        guard let chapter = try data(html)?["chapter"] as? [String: Any], let pages = chapter["pages"] as? [[String: Any]] else { return nil }
        guard (1...500).contains(pages.count) else { throw ReaderError.noPages }
        return try pages.map {
            guard let address = $0["url"] as? String, let url = URL(string: address.replacingOccurrences(of: " ", with: "%20")),
                  url.scheme == "https", url.host != nil else { throw ReaderError.invalidImage }
            return url
        }
    }
    static func pageCount(_ html: String) -> Int {
        min(500, max(1, capture(#"aria-label\s*=\s*["']Page (\d+)["']"#, in: html).compactMap(Int.init).max() ?? 1))
    }
    static func legacyImage(_ html: String, relativeTo base: URL) throws -> URL {
        let images = capture(#"(<img\b[^>]*>)"#, in: html)
        guard let tag = images.first(where: { $0.contains("img-responsive") }),
              let address = capture(#"\bsrc\s*=\s*["']([^"']+)["']"#, in: tag).first,
              let url = URL(string: address.replacingOccurrences(of: "&amp;", with: "&").replacingOccurrences(of: " ", with: "%20"), relativeTo: base)?.absoluteURL,
              url.scheme == "https" else { throw ReaderError.noPages }
        return url
    }
}
