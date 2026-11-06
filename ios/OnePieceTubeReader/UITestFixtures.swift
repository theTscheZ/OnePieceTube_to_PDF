#if DEBUG
import UIKit

@MainActor enum UITestFixtures {
    static func install() {
        guard ProcessInfo.processInfo.arguments.contains("--ui-testing") else { return }
        let store = LibraryStore.shared
        // The test launch argument selects a separate directory and preference suite.
        try? FileManager.default.removeItem(at: store.directory)
        try? FileManager.default.createDirectory(at: store.directory, withIntermediateDirectories: true)
        for chapter in [9, 10] {
            let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 300, height: 450))
            try? renderer.writePDF(to: store.url("OnePiece_\(chapter).pdf")) { context in
                for page in 1...2 {
                    context.beginPage(); UIColor.white.setFill(); context.cgContext.fill(CGRect(x: 0, y: 0, width: 300, height: 450))
                    "Kapitel \(chapter) · Seite \(page)".draw(at: CGPoint(x: 24, y: 24), withAttributes: [.foregroundColor: UIColor.black, .font: UIFont.systemFont(ofSize: 16)])
                }
            }
        }
        store.refresh(); store.mark(Set(store.chapters.map(\.name)), as: .unread)
    }
}
#endif
