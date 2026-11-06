import SwiftUI
import PDFKit

struct ReaderRequest: Identifiable { let id = UUID(); let name: String; let names: [String] }

struct MangaReader: View {
    let request: ReaderRequest
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var settings: ReaderSettings
    @Environment(\.dismiss) private var dismiss
    @State private var counts: [Int] = []
    @State private var position = MangaPosition(chapter: 0, page: 0)
    @State private var chrome = false
    @State private var loading = true
    @State private var jump = false
    @State private var jumpInput = ""
    private var name: String { request.names.indices.contains(position.chapter) ? request.names[position.chapter] : request.name }
    private var total: Int { counts.indices.contains(position.chapter) ? counts[position.chapter] : 0 }
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if loading { ProgressView().tint(settings.accent.color) }
            else if total > 0 {
                MangaPager(directory: library.directory, names: request.names, counts: counts, position: $position,
                    onTap: { withAnimation(.easeInOut(duration: 0.15)) { chrome.toggle() } })
                    .ignoresSafeArea()
            } else {
                ContentUnavailableView {
                    Label("PDF kann nicht geöffnet werden", systemImage: "exclamationmark.doc")
                } description: { Text("Die Datei ist beschädigt, leer oder passwortgeschützt.") } actions: {
                    if let next = counts.indices.first(where: { counts[$0] > 0 }) {
                        Button("Nächstes lesbares Kapitel") { position = MangaPosition(chapter: next, page: 0) }
                    }
                    Button("Schließen") { dismiss() }
                }
            }
            if chrome || total == 0 || loading {
                VStack {
                    ZStack {
                        Text(ChapterRules.number(name).map { "Kapitel \($0)" } ?? (name as NSString).deletingPathExtension)
                            .font(.headline).foregroundStyle(settings.accent.color).lineLimit(1).padding(.horizontal, 56)
                        HStack {
                            Spacer()
                            Button("Reader schließen", systemImage: "xmark") { dismiss() }
                                .labelStyle(.iconOnly).frame(width: 44, height: 44).accessibilityIdentifier("reader-close")
                        }
                    }.background(.black.opacity(0.88))
                    Spacer()
                    if total > 0 {
                        HStack {
                            Button("Nächste Seite", systemImage: "chevron.left") { move(true) }
                                .labelStyle(.iconOnly).frame(width: 48, height: 48)
                                .disabled(position.moving(forward: true, counts: counts) == nil)
                            Spacer()
                            Button("\(position.page + 1) / \(total)") { jumpInput = "\(position.page + 1)"; jump = true }
                                .frame(minWidth: 64, minHeight: 48)
                            Spacer()
                            Button("Vorherige Seite", systemImage: "chevron.right") { move(false) }
                                .labelStyle(.iconOnly).frame(width: 48, height: 48)
                                .disabled(position.moving(forward: false, counts: counts) == nil)
                        }.padding(.horizontal, LayoutSpace.medium).background(.black.opacity(0.88))
                    }
                }
            }
        }
        .statusBarHidden().persistentSystemOverlays(.hidden)
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        .task {
            let urls = request.names.map { library.url($0) }
            let result = await Task.detached(priority: .userInitiated) {
                urls.map { url -> Int in
                    autoreleasepool {
                        guard let pdf = PDFDocument(url: url), !pdf.isLocked else { return 0 }; return pdf.pageCount
                    }
                }
            }.value
            guard !Task.isCancelled else { return }
            counts = result
            let index = request.names.firstIndex(of: request.name) ?? 0
            guard result.indices.contains(index) else { loading = false; return }
            let page = min(library.record(request.name).page, max(0, result[index] - 1))
            position = MangaPosition(chapter: index, page: page); loading = false; savePosition()
        }
        .onChange(of: position) { _, _ in savePosition() }
        .alert("Zu Seite springen", isPresented: $jump) {
            TextField("Seite (1–\(total))", text: $jumpInput).keyboardType(.numberPad)
            Button("Öffnen") { if let page = Int(jumpInput), (1...total).contains(page) { position.page = page - 1 } }
            Button("Abbrechen", role: .cancel) { }
        }
    }
    private func move(_ forward: Bool) { if let next = position.moving(forward: forward, counts: counts) { position = next } }
    private func savePosition() { if total > 0 { library.savePosition(name: name, page: position.page, total: total) } }
}

struct MangaPager: UIViewControllerRepresentable {
    let directory: URL
    let names: [String]
    let counts: [Int]
    @Binding var position: MangaPosition
    let onTap: () -> Void
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIViewController(context: Context) -> UIPageViewController {
        let controller = UIPageViewController(transitionStyle: .scroll, navigationOrientation: .horizontal)
        controller.view.backgroundColor = .black
        controller.view.accessibilityIdentifier = "manga-pager"
        controller.dataSource = context.coordinator; controller.delegate = context.coordinator
        context.coordinator.controller = controller
        controller.setViewControllers([context.coordinator.page(position)], direction: .reverse, animated: false)
        context.coordinator.disableOuterScrolling()
        return controller
    }
    func updateUIViewController(_ controller: UIPageViewController, context: Context) {
        context.coordinator.parent = self
        context.coordinator.show(position)
    }

    @MainActor final class Coordinator: NSObject, UIPageViewControllerDataSource, UIPageViewControllerDelegate {
        var parent: MangaPager
        weak var controller: UIPageViewController?
        private var transitioning = false
        init(_ parent: MangaPager) { self.parent = parent }
        func page(_ position: MangaPosition) -> PDFPageController {
            let page = PDFPageController(url: parent.directory.appendingPathComponent(parent.names[position.chapter]), position: position)
            page.onTap = { [weak self] in self?.parent.onTap() }
            page.onSwipe = { [weak self, weak page] forward in
                guard let self, self.controller?.viewControllers?.first === page,
                      !self.transitioning, let current = page?.position,
                      let next = current.moving(forward: forward, counts: self.parent.counts) else { return }
                self.parent.position = next
            }
            return page
        }
        func disableOuterScrolling() {
            // PDFKit owns its inner scroll view. Fit-page swipes use explicit recognizers;
            // leaving two competing horizontal scroll views enabled loses gestures on iOS.
            controller?.view.subviews.compactMap { $0 as? UIScrollView }.forEach { $0.isScrollEnabled = false }
        }
        func show(_ position: MangaPosition) {
            guard let controller, !transitioning else { return }
            let current = (controller.viewControllers?.first as? PDFPageController)?.position
            guard current != position else { return }
            let forward = current.map { position.chapter > $0.chapter || (position.chapter == $0.chapter && position.page > $0.page) } ?? true
            transitioning = true
            controller.setViewControllers([page(position)], direction: forward ? .reverse : .forward, animated: true) { [weak self] _ in
                guard let self else { return }
                self.transitioning = false; self.disableOuterScrolling()
                if self.parent.position != position { self.show(self.parent.position) }
            }
        }
        // The page to the left is the next manga page; a rightward swipe reveals it.
        func pageViewController(_ controller: UIPageViewController, viewControllerBefore viewController: UIViewController) -> UIViewController? {
            guard let current = viewController as? PDFPageController, let next = current.position.moving(forward: true, counts: parent.counts) else { return nil }
            return page(next)
        }
        func pageViewController(_ controller: UIPageViewController, viewControllerAfter viewController: UIViewController) -> UIViewController? {
            guard let current = viewController as? PDFPageController, let previous = current.position.moving(forward: false, counts: parent.counts) else { return nil }
            return page(previous)
        }
        func pageViewController(_ controller: UIPageViewController, didFinishAnimating finished: Bool,
                                previousViewControllers: [UIViewController], transitionCompleted completed: Bool) {
            if completed, let page = controller.viewControllers?.first as? PDFPageController {
                parent.position = page.position; disableOuterScrolling()
            }
        }
    }
}

final class PDFPageController: UIViewController, UIGestureRecognizerDelegate {
    let position: MangaPosition
    let url: URL
    let pdf = PDFView()
    var onTap: (() -> Void)?
    var onSwipe: ((Bool) -> Void)?
    private var previousSize = CGSize.zero
    init(url: URL, position: MangaPosition) { self.url = url; self.position = position; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError("Storyboard initialization is unsupported") }
    override func viewDidLoad() {
        super.viewDidLoad(); view.backgroundColor = .black
        pdf.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(pdf)
        NSLayoutConstraint.activate([pdf.topAnchor.constraint(equalTo: view.topAnchor), pdf.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            pdf.leadingAnchor.constraint(equalTo: view.leadingAnchor), pdf.trailingAnchor.constraint(equalTo: view.trailingAnchor)])
        pdf.backgroundColor = .black; pdf.displayMode = .singlePage; pdf.displaysPageBreaks = false
        pdf.displayBox = .mediaBox; pdf.autoScales = true; pdf.isInMarkupMode = false
        if let source = PDFDocument(url: url), let page = source.page(at: position.page) {
            let document = PDFDocument(); document.insert(page, at: 0); pdf.document = document
        }
        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped)); tap.cancelsTouchesInView = false; tap.delegate = self
        view.addGestureRecognizer(tap)
        for direction in [UISwipeGestureRecognizer.Direction.left, .right] {
            let swipe = UISwipeGestureRecognizer(target: self, action: #selector(swiped(_:)))
            swipe.direction = direction; swipe.delegate = self; view.addGestureRecognizer(swipe)
        }
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard view.bounds.size != previousSize, view.bounds.width > 0, view.bounds.height > 0 else { return }
        previousSize = view.bounds.size
        let fit = pdf.scaleFactorForSizeToFit
        guard fit > 0 else { return }
        pdf.minScaleFactor = fit; pdf.maxScaleFactor = fit * 5; pdf.scaleFactor = fit
    }
    @objc private func tapped() { onTap?() }
    @objc private func swiped(_ gesture: UISwipeGestureRecognizer) { onSwipe?(gesture.direction == .right) }
    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        gestureRecognizer is UITapGestureRecognizer || pdf.scaleFactor <= pdf.minScaleFactor * 1.02
    }
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        if gestureRecognizer is UITapGestureRecognizer { return true }
        return gestureRecognizer is UISwipeGestureRecognizer && pdf.scaleFactor <= pdf.minScaleFactor * 1.02
    }
}
