import SwiftUI
import BackgroundTasks
import UserNotifications

@MainActor final class NotificationRoute: ObservableObject {
    static let shared = NotificationRoute()
    @Published var chapter: String?
}

@MainActor enum AutomaticScheduler {
    static let identifier = "at.aaron.onepiece.ios.refresh"
    static func schedule() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier)
        guard ReaderSettings.shared.keepCurrent else { DownloadModel.shared.cancelAutomatic(); return }
        let request = BGProcessingTaskRequest(identifier: identifier)
        request.requiresNetworkConnectivity = true
        request.earliestBeginDate = Date(timeIntervalSinceNow: 3600)
        // Scheduling can be unavailable in the simulator or when Background App Refresh is disabled.
        try? BGTaskScheduler.shared.submit(request)
    }
}

@MainActor final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        BGTaskScheduler.shared.register(forTaskWithIdentifier: AutomaticScheduler.identifier, using: .main) { background in
            let operation = Task { @MainActor in
                AutomaticScheduler.schedule()
                let success = await DownloadModel.shared.checkAutomatically()
                background.setTaskCompleted(success: success)
            }
            background.expirationHandler = { operation.cancel() }
        }
        return true
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler completion: @escaping () -> Void) {
        let name = response.notification.request.content.userInfo["chapter"] as? String
        Task { @MainActor in NotificationRoute.shared.chapter = name; completion() }
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                            withCompletionHandler completion: @escaping (UNNotificationPresentationOptions) -> Void) {
        completion([.banner, .list, .sound])
    }
}

@main @MainActor struct OnePieceTubeReaderApp: App {
    init() {
        #if DEBUG
        UITestFixtures.install()
        #endif
    }
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var library = LibraryStore.shared
    @StateObject private var settings = ReaderSettings.shared
    @StateObject private var download = DownloadModel.shared
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            LibraryView().environmentObject(library).environmentObject(settings).environmentObject(download)
                .tint(settings.accent.color).preferredColorScheme(.dark)
                .onOpenURL { url in if url.isFileURL { Task { await library.importPDFs([url]) } } }
                 .task {
                    AutomaticScheduler.schedule()
                    if settings.keepCurrent && Date().timeIntervalSince(settings.lastAutomaticCheck) >= 3600 {
                        _ = await download.checkAutomatically()
                    }
                }
                .onChange(of: phase) { _, phase in
                    if phase == .active {
                        library.refresh()
                        if settings.keepCurrent && Date().timeIntervalSince(settings.lastAutomaticCheck) >= 3600 {
                            Task { _ = await download.checkAutomatically() }
                        }
                    }
                    if phase == .background { AutomaticScheduler.schedule() }
                }
                .onChange(of: settings.keepCurrent) { _, enabled in
                    AutomaticScheduler.schedule()
                    if enabled {
                        if let highest = library.chapters.compactMap(\.number).max() { settings.rememberDownloaded(highest) }
                        Task {
                            _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
                            _ = await download.checkAutomatically()
                        }
                    }
                }
                .onChange(of: settings.wifiOnly) { _, _ in download.cancelAutomatic(); AutomaticScheduler.schedule() }
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject private var settings: ReaderSettings
    @Environment(\.dismiss) private var dismiss
    @State private var notificationsAllowed = true
    @Environment(\.scenePhase) private var phase
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Halte mich aktuell", isOn: $settings.keepCurrent).accessibilityIdentifier("keep-current")
                    Toggle("Nur im WLAN herunterladen", isOn: $settings.wifiOnly).disabled(!settings.keepCurrent)
                    if settings.keepCurrent && !notificationsAllowed {
                        Button("Benachrichtigungen erlauben") {
                            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                        }
                    }
                } header: { Text("Neue Kapitel") } footer: {
                    Text("Ohne Kapitel wird das neueste geladen, sonst alle nach deiner höchsten Kapitelnummer. Bereits geladene und später gelöschte Kapitel werden übersprungen. iOS entscheidet, wann Hintergrundprüfungen laufen können; dafür muss die Hintergrundaktualisierung erlaubt sein.")
                }
                Section("Akzentfarbe") {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: LayoutSpace.small), count: 3), spacing: LayoutSpace.small) {
                        ForEach(Accent.allCases) { accent in
                            Button { settings.accent = accent } label: {
                                VStack(spacing: LayoutSpace.small) {
                                    Image(systemName: settings.accent == accent ? "checkmark.circle.fill" : "circle.fill")
                                        .font(.title2).foregroundStyle(accent.color)
                                    Text(accent.title).font(.caption).foregroundStyle(.primary)
                                }.frame(maxWidth: .infinity, minHeight: 64)
                            }.buttonStyle(.plain).accessibilityIdentifier("accent-" + accent.rawValue)
                                .accessibilityAddTraits(settings.accent == accent ? .isSelected : [])
                        }
                    }.padding(.vertical, LayoutSpace.small)
                }
            }
            .navigationTitle("Einstellungen").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Schließen", systemImage: "xmark") { dismiss() }.labelStyle(.iconOnly) } }
            .task { await checkNotifications() }
            .onChange(of: phase) { _, value in if value == .active { Task { await checkNotifications() } } }
        }
    }
    private func checkNotifications() async {
        let state = await UNUserNotificationCenter.current().notificationSettings()
        notificationsAllowed = [.authorized, .provisional, .ephemeral].contains(state.authorizationStatus)
    }
}
