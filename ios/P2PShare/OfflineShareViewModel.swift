import Combine
import Foundation

@MainActor
final class OfflineShareViewModel: ObservableObject {
    @Published var profileName: String
    @Published var avatarImageData: Data?
    @Published private(set) var peers: [NativePeer] = []
    @Published private(set) var liveTransfers: [NativeTransfer] = []
    @Published private(set) var statusMessage: String = "Offline mode ready."
    @Published private(set) var isRunning = false
    @Published private(set) var history: [TransferHistoryItem]

    private let service = OfflineShareService()
    private let store = ProfileStore.shared
    private var cancellables = Set<AnyCancellable>()
    private var historyTransferIds = Set<UUID>()

    init() {
        self.profileName = store.loadName()
        self.avatarImageData = store.loadAvatarData()
        self.history = store.loadHistory()
        bindService()
    }

    func startIfNeeded() {
        guard !isRunning else { return }
        start()
    }

    func start() {
        service.start(displayName: profileName)
    }

    func stop() {
        service.stop()
    }

    func invite(peerId: String) {
        service.invite(peerId: peerId)
    }

    func sendFile(url: URL, toPeerId peerId: String) {
        service.sendResource(url: url, to: peerId)
    }

    func saveProfile() {
        let cleaned = profileName.trimmingCharacters(in: .whitespacesAndNewlines)
        profileName = cleaned.isEmpty ? "My iPhone" : String(cleaned.prefix(24))
        store.saveProfile(name: profileName, avatarData: avatarImageData)
        if isRunning {
            service.start(displayName: profileName)
        }
    }

    func clearHistory() {
        history.removeAll()
        store.saveHistory(history)
    }

    private func bindService() {
        service.$peers
            .receive(on: DispatchQueue.main)
            .sink { [weak self] peers in
                self?.peers = peers
            }
            .store(in: &cancellables)

        service.$liveTransfers
            .receive(on: DispatchQueue.main)
            .sink { [weak self] transfers in
                guard let self else { return }
                self.liveTransfers = transfers
                self.captureHistory(from: transfers)
            }
            .store(in: &cancellables)

        service.$statusMessage
            .receive(on: DispatchQueue.main)
            .sink { [weak self] message in
                self?.statusMessage = message
            }
            .store(in: &cancellables)

        service.$isRunning
            .receive(on: DispatchQueue.main)
            .sink { [weak self] running in
                self?.isRunning = running
            }
            .store(in: &cancellables)
    }

    private func captureHistory(from transfers: [NativeTransfer]) {
        for transfer in transfers where transfer.status == .completed {
            if historyTransferIds.contains(transfer.id) {
                continue
            }
            historyTransferIds.insert(transfer.id)
            history.insert(
                TransferHistoryItem(
                    id: transfer.id,
                    fileName: transfer.fileName,
                    peerName: transfer.peerName,
                    direction: transfer.direction,
                    timestamp: Date()
                ),
                at: 0
            )
        }
        store.saveHistory(history)
    }
}
