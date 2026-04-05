import Foundation

@MainActor
final class AppShellViewModel: ObservableObject {
    @Published var selectedSection: AppSection? = .home
    @Published var profileName: String = "My iPhone"
    @Published var joinCodeDraft: String = ""
    @Published var transportState = AppTransportState()

    private let offlineTransport: OfflineTransport
    private let onlineTransport: OnlineTransport

    init(
        offlineTransport: OfflineTransport = NearbyOfflineTransport(),
        onlineTransport: OnlineTransport = RealtimeOnlineTransport()
    ) {
        self.offlineTransport = offlineTransport
        self.onlineTransport = onlineTransport
        self.offlineTransport.delegate = self
        self.onlineTransport.delegate = self
    }

    func selectMode(_ mode: ShareMode) {
        transportState.mode = mode
    }

    func startOffline() {
        offlineTransport.start(displayName: profileName)
    }

    func stopOffline() {
        offlineTransport.stop()
    }

    func startOnline() {
        onlineTransport.connect(displayName: profileName)
    }

    func stopOnline() {
        onlineTransport.disconnect()
    }

    func hostRoom() {
        onlineTransport.hostRoom()
    }

    func joinRoom() {
        onlineTransport.joinRoom(code: joinCodeDraft)
    }

    func leaveRoom() {
        onlineTransport.leaveRoom()
    }
}

extension AppShellViewModel: OfflineTransportDelegate {
    func offlineTransportDidChange(active: Bool, status: String) {
        transportState.offlineActive = active
        transportState.offlineStatus = status
    }
}

extension AppShellViewModel: OnlineTransportDelegate {
    func onlineTransportDidChange(active: Bool, status: String, roomCode: String?, peers: Int) {
        transportState.onlineActive = active
        transportState.onlineStatus = status
        transportState.roomCode = roomCode
        transportState.connectedPeers = peers
    }
}
