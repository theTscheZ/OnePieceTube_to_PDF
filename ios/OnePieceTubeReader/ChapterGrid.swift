import SwiftUI
import UIKit
import PDFKit

struct GallerySweep {
    let names: [String]
    let baseline: Set<String>
    let anchor: Int
    func selection(at endpoint: Int) -> Set<String> {
        guard names.indices.contains(anchor), names.indices.contains(endpoint) else { return baseline }
        let range = Set(names[min(anchor, endpoint)...max(anchor, endpoint)])
        return baseline.contains(names[anchor]) ? baseline.subtracting(range) : baseline.union(range)
    }
    static func velocity(y: CGFloat, height: CGFloat, edge: CGFloat = 84, maximum: CGFloat = 1400) -> CGFloat {
        let edge = min(edge, height / 3)
        guard edge > 0 else { return 0 }
        if y < edge { return -maximum * pow(min(1, max(0, (edge - y) / edge)), 2) }
        if y > height - edge { return maximum * pow(min(1, max(0, (y - height + edge) / edge)), 2) }
        return 0
    }
}

struct ChapterGrid: UIViewControllerRepresentable {
    let chapters: [Chapter]
    let directory: URL
    let records: [String: ReadingRecord]
    let accent: Color
    let editing: Bool
    let selection: Set<String>
    let onOpen: (Chapter) -> Void
    let onSelection: (Set<String>) -> Void
    let onStartSelection: () -> Void
    let onAction: (Chapter) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIViewController(context: Context) -> UICollectionViewController {
        let layout = UICollectionViewFlowLayout()
        layout.minimumInteritemSpacing = LayoutSpace.medium; layout.minimumLineSpacing = LayoutSpace.medium
        let controller = UICollectionViewController(collectionViewLayout: layout)
        controller.collectionView.backgroundColor = .clear
        controller.collectionView.contentInset = UIEdgeInsets(top: 0, left: LayoutSpace.medium, bottom: LayoutSpace.medium, right: LayoutSpace.medium)
        controller.collectionView.register(UICollectionViewCell.self, forCellWithReuseIdentifier: "chapter")
        controller.collectionView.dataSource = context.coordinator; controller.collectionView.delegate = context.coordinator
        let gesture = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.sweep(_:)))
        gesture.minimumPressDuration = 0.4
        controller.collectionView.addGestureRecognizer(gesture)
        context.coordinator.collection = controller.collectionView
        return controller
    }
    func updateUIViewController(_ controller: UICollectionViewController, context: Context) {
        let previous = context.coordinator.parent
        context.coordinator.parent = self
        if previous.chapters != chapters {
            context.coordinator.endSweep(); controller.collectionView.reloadData()
        } else if previous.selection != selection || previous.editing != editing || previous.records != records || previous.accent != accent {
            // Keep the recognizer and scroll position while selection changes under the finger.
            for path in controller.collectionView.indexPathsForVisibleItems {
                if let cell = controller.collectionView.cellForItem(at: path) { context.coordinator.configure(cell, at: path) }
            }
        }
        controller.collectionView.collectionViewLayout.invalidateLayout()
    }
    static func dismantleUIViewController(_ controller: UICollectionViewController, coordinator: Coordinator) { coordinator.endSweep() }

    @MainActor final class Coordinator: NSObject, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
        var parent: ChapterGrid
        weak var collection: UICollectionView?
        var gesture: GallerySweep?
        var fingerInWindow = CGPoint.zero
        var displayLink: CADisplayLink?
        var lastTick: CFTimeInterval = 0
        init(_ parent: ChapterGrid) { self.parent = parent }
        func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int { parent.chapters.count }
        func configure(_ cell: UICollectionViewCell, at path: IndexPath) {
            guard parent.chapters.indices.contains(path.item) else { return }
            let chapter = parent.chapters[path.item]
            cell.contentConfiguration = UIHostingConfiguration {
                ChapterCard(chapter: chapter, url: parent.directory.appendingPathComponent(chapter.name),
                    record: parent.records[chapter.name] ?? ReadingRecord(), editing: parent.editing,
                    selected: parent.selection.contains(chapter.name), accent: parent.accent, onAction: { [weak self] in self?.parent.onAction(chapter) })
            }.margins(.all, 0)
            cell.isAccessibilityElement = true
            cell.accessibilityIdentifier = "chapter-" + chapter.name
            cell.accessibilityLabel = chapter.title + ", " + (parent.records[chapter.name]?.status.title ?? "Ungelesen")
            cell.accessibilityTraits = parent.selection.contains(chapter.name) ? [.button, .selected] : .button
            cell.accessibilityCustomActions = [UIAccessibilityCustomAction(name: "Kapitel-Aktionen", actionHandler: { [weak self] _ in
                self?.parent.onAction(chapter); return true
            })]
        }
        func collectionView(_ collectionView: UICollectionView, cellForItemAt path: IndexPath) -> UICollectionViewCell {
            let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "chapter", for: path); configure(cell, at: path); return cell
        }
        func collectionView(_ collectionView: UICollectionView, layout: UICollectionViewLayout, sizeForItemAt path: IndexPath) -> CGSize {
            let width = collectionView.bounds.width - 2 * LayoutSpace.medium
            let count = max(2, floor((width + LayoutSpace.medium) / (148 + LayoutSpace.medium)))
            let cellWidth = floor((width - (count - 1) * LayoutSpace.medium) / count)
            return CGSize(width: cellWidth, height: cellWidth * 1.32 + 72)
        }
        func collectionView(_ collectionView: UICollectionView, didSelectItemAt path: IndexPath) {
            let chapter = parent.chapters[path.item]
            if parent.editing {
                var names = parent.selection
                if names.contains(chapter.name) { names.remove(chapter.name) } else { names.insert(chapter.name) }
                parent.onSelection(names)
            } else { parent.onOpen(chapter) }
        }
        func collectionView(_ collectionView: UICollectionView, contextMenuConfigurationForItemAt path: IndexPath, point: CGPoint) -> UIContextMenuConfiguration? {
            // A long press is reserved for gallery selection. Actions are available from the library toolbar.
            nil
        }
        @objc func sweep(_ recognizer: UILongPressGestureRecognizer) {
            guard let collection else { return }
            fingerInWindow = recognizer.location(in: collection.window)
            switch recognizer.state {
            case .began:
                guard let path = collection.indexPathForItem(at: recognizer.location(in: collection)) else { return }
                gesture = GallerySweep(names: parent.chapters.map(\.name), baseline: parent.selection, anchor: path.item)
                collection.isScrollEnabled = false
                parent.onStartSelection(); parent.onSelection(gesture!.selection(at: path.item))
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                lastTick = 0
                let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
                link.add(to: .main, forMode: .common); displayLink = link
            case .changed: updateSelection()
            default: endSweep()
            }
        }
        func updateSelection() {
            guard let collection, let gesture else { return }
            let point = collection.convert(fingerInWindow, from: collection.window)
            let attributes = collection.collectionViewLayout.layoutAttributesForElements(in: collection.bounds) ?? []
            let nearest = attributes.filter { $0.representedElementCategory == .cell }.min {
                hypot($0.center.x - point.x, $0.center.y - point.y) < hypot($1.center.x - point.x, $1.center.y - point.y)
            }
            if let index = collection.indexPathForItem(at: point)?.item ?? nearest?.indexPath.item { parent.onSelection(gesture.selection(at: index)) }
        }
        @objc func tick(_ link: CADisplayLink) {
            guard let collection, gesture != nil else { endSweep(); return }
            let dt = lastTick == 0 ? 0 : min(link.timestamp - lastTick, 0.04); lastTick = link.timestamp
            let point = collection.convert(fingerInWindow, from: collection.window)
            let velocity = GallerySweep.velocity(y: point.y - collection.contentOffset.y, height: collection.bounds.height)
            let minimum = -collection.adjustedContentInset.top
            let maximum = max(minimum, collection.contentSize.height - collection.bounds.height + collection.adjustedContentInset.bottom)
            let y = min(maximum, max(minimum, collection.contentOffset.y + velocity * CGFloat(dt)))
            collection.setContentOffset(CGPoint(x: collection.contentOffset.x, y: y), animated: false)
            updateSelection()
        }
        func endSweep() { displayLink?.invalidate(); displayLink = nil; gesture = nil; collection?.isScrollEnabled = true }
    }
}

struct ChapterCard: View {
    let chapter: Chapter
    let url: URL
    let record: ReadingRecord
    let editing: Bool
    let selected: Bool
    let accent: Color
    let onAction: () -> Void
    @State private var thumbnail: UIImage?
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            GeometryReader { geometry in
                ZStack(alignment: .topTrailing) {
                    Color(uiColor: .secondarySystemGroupedBackground)
                    if let thumbnail { Image(uiImage: thumbnail).resizable().scaledToFit().frame(width: geometry.size.width, height: geometry.size.height) }
                    else { Image(systemName: "doc.richtext").font(.largeTitle).foregroundStyle(accent).frame(maxWidth: .infinity, maxHeight: .infinity) }
                    if editing {
                        if selected { accent.opacity(0.18) }
                        Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                            .font(.title2).foregroundStyle(accent).padding(LayoutSpace.small)
                    }
                }
            }
            HStack(spacing: 0) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(chapter.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                    Text(record.status.title).font(.caption).foregroundStyle(accent)
                    Text(ByteCountFormatter.string(fromByteCount: chapter.bytes, countStyle: .file)).font(.caption2).foregroundStyle(.secondary)
                }.frame(maxWidth: .infinity, alignment: .leading)
                if !editing {
                    Button("Kapitel-Aktionen", systemImage: "ellipsis") { onAction() }.labelStyle(.iconOnly).frame(width: 44, height: 44)
                }
            }.padding(LayoutSpace.small)
        }.background(Color(uiColor: .secondarySystemGroupedBackground)).clipShape(RoundedRectangle(cornerRadius: LayoutSpace.medium))
            .overlay(RoundedRectangle(cornerRadius: LayoutSpace.medium).stroke(selected ? accent : .clear, lineWidth: 2))
            .task(id: chapter) {
                let image = await ThumbnailCache.shared.image(url: url, modified: chapter.modified)
                if !Task.isCancelled { thumbnail = image }
            }
    }
}

private actor ThumbnailCache {
    static let shared = ThumbnailCache()
    private let cache = NSCache<NSString, UIImage>()
    init() { cache.totalCostLimit = 24 * 1024 * 1024 }
    func image(url: URL, modified: Date) -> UIImage? {
        let key = (url.path + ":" + String(modified.timeIntervalSince1970)) as NSString
        if let image = cache.object(forKey: key) { return image }
        return autoreleasepool {
            guard let pdf = PDFDocument(url: url), !pdf.isLocked, let page = pdf.page(at: 0) else { return nil }
            let image = page.thumbnail(of: CGSize(width: 240, height: 340), for: .mediaBox)
            cache.setObject(image, forKey: key, cost: Int(image.size.width * image.size.height * 4))
            return image
        }
    }
}
