import SwiftUI
import UniformTypeIdentifiers

private enum LibrarySort: String, CaseIterable, Identifiable {
    case ascending, descending, newest, largest
    var id: String { rawValue }
    var title: String { switch self { case .ascending: "Kapitel aufsteigend"; case .descending: "Kapitel absteigend"; case .newest: "Zuletzt hinzugefügt"; case .largest: "Größte zuerst" } }
}
private struct ShareRequest: Identifiable { let id = UUID(); let url: URL }

struct LibraryView: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var settings: ReaderSettings
    @EnvironmentObject private var download: DownloadModel
    @ObservedObject private var route = NotificationRoute.shared
    @State private var query = ""
    @State private var sort = LibrarySort.ascending
    @State private var filter: ReadStatus?
    @State private var editing = false
    @State private var selection: Set<String> = []
    @State private var settingsOpen = false
    @State private var importing = false
    @State private var importBusy = false
    @State private var downloadsOpen = false
    @State private var reader: ReaderRequest?
    @State private var action: Chapter?
    @State private var actionsOpen = false
    @State private var rename: Chapter?
    @State private var renameText = ""
    @State private var renaming = false
    @State private var deleteNames: Set<String> = []
    @State private var deleteOpen = false
    @State private var share: ShareRequest?
    private var visible: [Chapter] {
        let chapters = library.chapters.filter { (query.isEmpty || $0.name.localizedCaseInsensitiveContains(query)) && (filter == nil || library.record($0.name).status == filter) }
        switch sort {
        case .ascending: return chapters
        case .descending: return chapters.reversed()
        case .newest: return chapters.sorted { $0.modified > $1.modified }
        case .largest: return chapters.sorted { $0.bytes > $1.bytes }
        }
    }
    var body: some View {
        NavigationStack {
            VStack(spacing: LayoutSpace.medium) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: LayoutSpace.small) {
                        filterButton("Alle", value: nil)
                        ForEach(ReadStatus.allCases) { state in filterButton(state.title, value: state) }
                    }.padding(.horizontal, LayoutSpace.medium)
                }.disabled(editing).padding(.top, LayoutSpace.small)
                if let last = library.chapters.first(where: { $0.name == library.lastName }) {
                    Button { open(last) } label: {
                        HStack(spacing: LayoutSpace.medium) {
                            Image(systemName: "book.pages.fill").font(.title2)
                            VStack(alignment: .leading, spacing: 4) {
                                Text("WEITERLESEN").font(.caption.weight(.semibold))
                                Text(last.title).font(.headline).foregroundStyle(.primary).lineLimit(1)
                                Text("Seite \(library.record(last.name).page + 1)").font(.caption).foregroundStyle(.secondary)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                            Image(systemName: "chevron.left")
                        }.padding(LayoutSpace.medium).background(settings.accent.color.opacity(0.12), in: RoundedRectangle(cornerRadius: LayoutSpace.medium))
                    }.buttonStyle(.plain).padding(.horizontal, LayoutSpace.medium).disabled(editing)
                }
                if importBusy { ProgressView("PDFs werden importiert …") }
                if download.busy {
                    HStack(spacing: LayoutSpace.medium) {
                        ProgressView()
                        Text(download.progress).font(.subheadline).frame(maxWidth: .infinity, alignment: .leading)
                        Button("Abbrechen", systemImage: "xmark") { download.cancel() }.labelStyle(.iconOnly).frame(width: 44, height: 44)
                    }.padding(.horizontal, LayoutSpace.medium)
                }
                if visible.isEmpty {
                    ContentUnavailableView {
                        Label(library.chapters.isEmpty ? "Dein nächstes Kapitel wartet" : "Keine Treffer", systemImage: "books.vertical")
                    } description: {
                        Text(library.chapters.isEmpty ? "Importiere PDFs oder lade Kapitel direkt auf dein iPhone." : "Ändere den Filter oder Suchbegriff.")
                    } actions: { if library.chapters.isEmpty { Button("PDFs importieren") { importing = true } } }
                    .frame(maxHeight: .infinity)
                } else {
                    ChapterGrid(chapters: visible, directory: library.directory, records: library.records, accent: settings.accent.color,
                        editing: editing, selection: selection, onOpen: open,
                        onSelection: { selection = $0 }, onStartSelection: { editing = true }, onAction: {
                            action = $0; actionsOpen = true
                        })
                }
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle(editing ? "\(selection.count) ausgewählt" : "Bibliothek")
            .navigationBarTitleDisplayMode(editing ? .inline : .large)
            .searchable(text: $query, prompt: "Kapitel oder Dateiname suchen")
            .toolbar {
                if editing {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Auswahl verlassen", systemImage: "xmark") { exitSelection() }.labelStyle(.iconOnly)
                    }
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        Button(allVisibleSelected ? "Alle abwählen" : "Alle auswählen", systemImage: allVisibleSelected ? "checkmark.circle.fill" : "checkmark.circle") {
                            let names = Set(visible.map(\.name)); selection = allVisibleSelected ? selection.subtracting(names) : selection.union(names)
                        }.labelStyle(.iconOnly)
                        Menu("Lesestatus", systemImage: "book") {
                            ForEach(ReadStatus.allCases) { state in Button("Als \(state.title.lowercased()) markieren") { library.mark(selection, as: state) } }
                        }.labelStyle(.iconOnly).disabled(selection.isEmpty)
                        Button("Löschen", systemImage: "trash", role: .destructive) { confirmDeletion(selection) }.labelStyle(.iconOnly).disabled(selection.isEmpty)
                    }
                } else {
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        Button("Einstellungen", systemImage: "gearshape") { settingsOpen = true }.labelStyle(.iconOnly).accessibilityIdentifier("settings-button")
                        Button("PDFs importieren", systemImage: "plus") { importing = true }.labelStyle(.iconOnly).disabled(importBusy)
                        Menu("Sortieren und aufräumen", systemImage: "ellipsis.circle") {
                            Picker("Sortierung", selection: $sort) { ForEach(LibrarySort.allCases) { Text($0.title).tag($0) } }
                            Button("Auswählen", systemImage: "checkmark.circle") { editing = true }.disabled(library.chapters.isEmpty)
                            Button("Gelesene Kapitel löschen", systemImage: "trash", role: .destructive) {
                                confirmDeletion(Set(library.chapters.filter { library.record($0.name).status == .read }.map(\.name)))
                            }.disabled(!library.chapters.contains { library.record($0.name).status == .read })
                        }.labelStyle(.iconOnly)
                    }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if !editing {
                    Button { downloadsOpen = true } label: { Label("Kapitel laden", systemImage: "arrow.down.to.line").frame(maxWidth: .infinity, minHeight: 32) }
                        .buttonStyle(.borderedProminent).foregroundStyle(.black)
                        .padding(LayoutSpace.medium).background(.bar)
                }
            }
            .overlay(alignment: .bottom) {
                if let message = library.message {
                    HStack(spacing: LayoutSpace.small) {
                        Text(message).font(.subheadline).frame(maxWidth: .infinity, alignment: .leading)
                        Button("Meldung schließen", systemImage: "xmark") { library.message = nil }.labelStyle(.iconOnly).frame(width: 44, height: 44)
                    }.padding(.leading, LayoutSpace.medium).padding(.trailing, LayoutSpace.small)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: LayoutSpace.medium))
                        .padding(LayoutSpace.medium).padding(.bottom, editing ? 0 : 80)
                        .accessibilityElement(children: .combine)
                }
            }
            .task(id: library.message) {
                guard let message = library.message else { return }
                do { try await Task.sleep(for: .seconds(UIAccessibility.isVoiceOverRunning ? 8 : 4)) } catch { return }
                if library.message == message { library.message = nil }
            }
            .sheet(isPresented: $settingsOpen) { SettingsView() }
            .sheet(isPresented: $downloadsOpen) { DownloadSheet(defaultChapter: ChapterRules.nextChapter(library.chapters.map(\.name))) }
            .sheet(item: $share) { ActivitySheet(url: $0.url) }
            .fullScreenCover(item: $reader) { MangaReader(request: $0).id($0.id) }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.pdf], allowsMultipleSelection: true) { result in
                switch result {
                case .success(let urls): Task { importBusy = true; await library.importPDFs(urls); importBusy = false }
                case .failure(let error): library.message = error.localizedDescription
                }
            }
            .confirmationDialog(action?.title ?? "Kapitel", isPresented: $actionsOpen, titleVisibility: .visible) {
                if let action {
                    Button("Im Reader öffnen") { open(action) }
                    Button("Umbenennen") { rename = action; renameText = action.title; renaming = true }
                    Button("Teilen / In Dateien sichern") { share = ShareRequest(url: library.url(action.name)) }
                    ForEach(ReadStatus.allCases) { state in Button("Als \(state.title.lowercased()) markieren") { library.mark([action.name], as: state) } }
                    Button("Löschen", role: .destructive) { confirmDeletion([action.name]) }
                }
            }
            .alert("PDF umbenennen", isPresented: $renaming) {
                TextField("Dateiname ohne .pdf", text: $renameText)
                Button("Speichern") { if let rename { do { try library.rename(rename.name, to: renameText) } catch { library.message = error.localizedDescription } } }
                Button("Abbrechen", role: .cancel) { }
            }
            .alert("\(deleteNames.count) Kapitel löschen?", isPresented: $deleteOpen) {
                Button("Löschen", role: .destructive) {
                    let failures = library.delete(deleteNames)
                    if failures.isEmpty { exitSelection() } else { editing = true; selection = failures }
                    deleteNames = []
                }
                Button("Abbrechen", role: .cancel) { deleteNames = [] }
            } message: { Text("Die ausgewählten PDFs werden aus der App entfernt. Ursprünglich importierte Dateien bleiben erhalten.") }
            .onChange(of: library.chapters) { _, files in selection.formIntersection(Set(files.map(\.name))) }
            .onChange(of: route.chapter) { _, _ in openNotification() }
            .task { openNotification() }
        }
    }
    private var allVisibleSelected: Bool { !visible.isEmpty && visible.allSatisfy { selection.contains($0.name) } }
    private func filterButton(_ title: String, value: ReadStatus?) -> some View {
        Button { filter = value } label: { Text(title).font(.subheadline).padding(.vertical, 4) }
            .buttonStyle(.bordered).tint(filter == value ? settings.accent.color : .gray)
            .accessibilityAddTraits(filter == value ? .isSelected : [])
    }
    private func open(_ chapter: Chapter) { reader = ReaderRequest(name: chapter.name, names: library.chapters.map(\.name)) }
    private func exitSelection() { editing = false; selection = [] }
    private func confirmDeletion(_ names: Set<String>) { deleteNames = names; deleteOpen = !names.isEmpty }
    private func openNotification() {
        guard let name = route.chapter else { return }; route.chapter = nil
        if let chapter = library.chapters.first(where: { $0.name == name }) { settingsOpen = false; open(chapter) }
        else { library.message = "Kapitel ist nicht mehr in der Bibliothek" }
    }
}

struct DownloadSheet: View {
    let defaultChapter: Int
    @EnvironmentObject private var download: DownloadModel
    @Environment(\.dismiss) private var dismiss
    @State private var mode = DownloadMode.latest
    @State private var start = ""
    @State private var rangeStart = "420"
    @State private var end = "430"
    private var first: Int { Int(mode == .range ? rangeStart : start) ?? 0 }
    private var last: Int { Int(end) ?? 0 }
    private var valid: Bool {
        if mode == .latest || mode == .all { return true }
        guard (1...100000).contains(first) else { return false }
        return mode != .range || (first...100000).contains(last)
    }
    var body: some View {
        NavigationStack {
            Form {
                Section { Picker("Download", selection: $mode) { ForEach(DownloadMode.allCases) { Text($0.title).tag($0) } }.pickerStyle(.inline) }
                if mode == .single || mode == .from {
                    Section { TextField(mode == .single ? "Kapitel" : "Ab Kapitel", text: $start).keyboardType(.numberPad) }
                }
                if mode == .range {
                    Section {
                        TextField("Von Kapitel", text: $rangeStart).keyboardType(.numberPad)
                        TextField("Bis Kapitel", text: $end).keyboardType(.numberPad)
                    }
                }
                Section {
                    Button("Herunterladen") { download.start(mode: mode, first: first, last: last); dismiss() }.disabled(!valid || download.busy)
                } footer: { Text(download.busy ? "Ein Download läuft bereits. Du kannst ihn in der Bibliothek abbrechen." : "Vorhandene Kapitel werden übersprungen. Manuelle Downloads können mobile Daten verwenden. Größere Bereiche am besten bei geöffneter App laden.") }
            }.navigationTitle("Kapitel laden").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Schließen", systemImage: "xmark") { dismiss() }.labelStyle(.iconOnly) } }
                .onAppear { if start.isEmpty { start = String(defaultChapter) } }
        }
    }
}

private struct ActivitySheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: [url], applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) { }
}
