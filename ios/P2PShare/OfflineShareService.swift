import Foundation
import MultipeerConnectivity

@MainActor
final class OfflineShareService: NSObject, ObservableObject {
    @Published private(set) var peers: [NativePeer] = []
    @Published private(set) var liveTransfers: [NativeTransfer] = []
    @Published var statusMessage: String = "Offline mode ready."
    @Published private(set) var isRunning = false

    private var myPeerId: MCPeerID?
    private var session: MCSession?
    private var advertiser: MCNearbyServiceAdvertiser?
    private var browser: MCNearbyServiceBrowser?

    private var peerLookup: [String: MCPeerID] = [:]
    private var peerStates: [String: PeerState] = [:]
    private var transferById: [UUID: NativeTransfer] = [:]
    private var incomingTransferByResourceName: [String: UUID] = [:]
    private var progressObservers: [UUID: NSKeyValueObservation] = [:]

    func start(displayName: String) {
        stop()

        let safeName = sanitizeName(displayName)
        let myPeerId = MCPeerID(displayName: safeName)
        let session = MCSession(peer: myPeerId, securityIdentity: nil, encryptionPreference: .required)
        session.delegate = self

        let advertiser = MCNearbyServiceAdvertiser(peer: myPeerId, discoveryInfo: ["name": safeName], serviceType: serviceType)
        advertiser.delegate = self
        advertiser.startAdvertisingPeer()

        let browser = MCNearbyServiceBrowser(peer: myPeerId, serviceType: serviceType)
        browser.delegate = self
        browser.startBrowsingForPeers()

        self.myPeerId = myPeerId
        self.session = session
        self.advertiser = advertiser
        self.browser = browser
        self.isRunning = true
        self.statusMessage = "Nearby discovery started."
    }

    func stop() {
        advertiser?.stopAdvertisingPeer()
        browser?.stopBrowsingForPeers()
        session?.disconnect()

        advertiser = nil
        browser = nil
        session = nil
        myPeerId = nil
        isRunning = false

        peerLookup.removeAll()
        peerStates.removeAll()
        peers.removeAll()
    }

    func invite(peerId: String) {
        guard
            let browser,
            let target = peerLookup[peerId],
            let session
        else { return }

        peerStates[peerId] = .connecting
        publishPeers()
        browser.invitePeer(target, to: session, withContext: nil, timeout: 20)
        statusMessage = "Pair request sent to \(target.displayName)."
    }

    func sendResource(url: URL, to peerId: String) {
        guard
            let session,
            let target = peerLookup[peerId],
            session.connectedPeers.contains(target)
        else {
            statusMessage = "Pair the device before sending files."
            return
        }

        let transferId = UUID()
        let transfer =
            NativeTransfer(
                id: transferId,
                peerId: peerId,
                peerName: target.displayName,
                fileName: url.lastPathComponent,
                direction: .outgoing,
                status: .queued,
                progress: 0,
                timestamp: Date()
            )
        transferById[transferId] = transfer
        publishTransfers()

        let progress =
            session.sendResource(at: url, withName: url.lastPathComponent, toPeer: target) { [weak self] error in
                guard let self else { return }
                Task { @MainActor in
                    let current = self.transferById[transferId] ?? transfer
                    let nextStatus: TransferStatus = (error == nil) ? .completed : .failed
                    self.transferById[transferId] = currentWith(status: nextStatus, progress: 1, base: current)
                    self.publishTransfers()
                    self.progressObservers[transferId] = nil
                    self.statusMessage =
                        error == nil
                        ? "Sent \(current.fileName) to \(current.peerName)."
                        : "Send failed for \(current.fileName)."
                }
            }

        progressObservers[transferId] =
            progress.observe(\.fractionCompleted, options: [.new]) { [weak self] progress, _ in
                guard let self else { return }
                Task { @MainActor in
                    guard let current = self.transferById[transferId] else { return }
                    self.transferById[transferId] = currentWith(status: .inProgress, progress: progress.fractionCompleted, base: current)
                    self.publishTransfers()
                }
            }
    }

    private func publishPeers() {
        peers =
            peerLookup
                .map { key, peer in
                    NativePeer(
                        id: key,
                        displayName: peer.displayName,
                        state: peerStates[key] ?? .discovered,
                        lastSeen: Date()
                    )
                }
                .sorted(by: { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending })
    }

    private func publishTransfers() {
        liveTransfers = transferById.values.sorted(by: { $0.timestamp > $1.timestamp })
    }

    private func registerPeer(_ peer: MCPeerID, state: PeerState) {
        let key = keyForPeer(peer)
        peerLookup[key] = peer
        peerStates[key] = state
        publishPeers()
    }

    private func removePeer(_ peer: MCPeerID) {
        let key = keyForPeer(peer)
        peerLookup.removeValue(forKey: key)
        peerStates.removeValue(forKey: key)
        publishPeers()
    }

    private func keyForPeer(_ peer: MCPeerID) -> String {
        "\(peer.displayName)#\(peer.hash)"
    }

    private func currentWith(status: TransferStatus, progress: Double, base: NativeTransfer) -> NativeTransfer {
        NativeTransfer(
            id: base.id,
            peerId: base.peerId,
            peerName: base.peerName,
            fileName: base.fileName,
            direction: base.direction,
            status: status,
            progress: min(max(progress, 0), 1),
            timestamp: base.timestamp
        )
    }

    private func sanitizeName(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return "My iPhone" }
        return String(trimmed.prefix(24))
    }

    private func persistIncomingResource(name: String, url: URL) throws -> URL {
        let documentsDir =
            FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let folder = documentsDir.appendingPathComponent("ShareViaReceived", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)

        let destination = uniqueDestination(in: folder, fileName: name)
        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.copyItem(at: url, to: destination)
        return destination
    }

    private func uniqueDestination(in directory: URL, fileName: String) -> URL {
        let ext = (fileName as NSString).pathExtension
        let stem = (fileName as NSString).deletingPathExtension
        var candidate = directory.appendingPathComponent(fileName)
        var index = 1
        while FileManager.default.fileExists(atPath: candidate.path) {
            let fallback = ext.isEmpty ? "\(stem) (\(index))" : "\(stem) (\(index)).\(ext)"
            candidate = directory.appendingPathComponent(fallback)
            index += 1
        }
        return candidate
    }

    deinit {
        advertiser?.stopAdvertisingPeer()
        browser?.stopBrowsingForPeers()
        session?.disconnect()
    }

    private let serviceType = "sv-nearby-v1"
}

extension OfflineShareService: MCNearbyServiceAdvertiserDelegate {
    nonisolated func advertiser(
        _ advertiser: MCNearbyServiceAdvertiser,
        didReceiveInvitationFromPeer peerID: MCPeerID,
        withContext context: Data?,
        invitationHandler: @escaping (Bool, MCSession?) -> Void
    ) {
        Task { @MainActor in
            registerPeer(peerID, state: .connecting)
            invitationHandler(true, session)
            statusMessage = "Pairing with \(peerID.displayName)..."
        }
    }

    nonisolated func advertiser(
        _ advertiser: MCNearbyServiceAdvertiser,
        didNotStartAdvertisingPeer error: Error
    ) {
        Task { @MainActor in
            statusMessage = "Advertise failed: \(error.localizedDescription)"
        }
    }
}

extension OfflineShareService: MCNearbyServiceBrowserDelegate {
    nonisolated func browser(
        _ browser: MCNearbyServiceBrowser,
        foundPeer peerID: MCPeerID,
        withDiscoveryInfo info: [String: String]?
    ) {
        Task { @MainActor in
            registerPeer(peerID, state: .discovered)
        }
    }

    nonisolated func browser(
        _ browser: MCNearbyServiceBrowser,
        lostPeer peerID: MCPeerID
    ) {
        Task { @MainActor in
            removePeer(peerID)
        }
    }

    nonisolated func browser(
        _ browser: MCNearbyServiceBrowser,
        didNotStartBrowsingForPeers error: Error
    ) {
        Task { @MainActor in
            statusMessage = "Browse failed: \(error.localizedDescription)"
        }
    }
}

extension OfflineShareService: MCSessionDelegate {
    nonisolated func session(_ session: MCSession, peer peerID: MCPeerID, didChange state: MCSessionState) {
        Task { @MainActor in
            let peerState: PeerState
            switch state {
            case .connected:
                peerState = .connected
            case .connecting:
                peerState = .connecting
            default:
                peerState = .discovered
            }
            registerPeer(peerID, state: peerState)
            statusMessage = "\(peerID.displayName): \(peerState.label)"
        }
    }

    nonisolated func session(_ session: MCSession, didReceive data: Data, fromPeer peerID: MCPeerID) {}

    nonisolated func session(
        _ session: MCSession,
        didReceive stream: InputStream,
        withName streamName: String,
        fromPeer peerID: MCPeerID
    ) {}

    nonisolated func session(
        _ session: MCSession,
        didStartReceivingResourceWithName resourceName: String,
        fromPeer peerID: MCPeerID,
        with progress: Progress
    ) {
        Task { @MainActor in
            let peerId = keyForPeer(peerID)
            let transferId = UUID()
            incomingTransferByResourceName[resourceName] = transferId
            let base =
                NativeTransfer(
                    id: transferId,
                    peerId: peerId,
                    peerName: peerID.displayName,
                    fileName: resourceName,
                    direction: .incoming,
                    status: .queued,
                    progress: 0,
                    timestamp: Date()
                )
            transferById[transferId] = base
            publishTransfers()

            progressObservers[transferId] =
                progress.observe(\.fractionCompleted, options: [.new]) { [weak self] progress, _ in
                    guard let self else { return }
                    Task { @MainActor in
                        guard let current = self.transferById[transferId] else { return }
                        self.transferById[transferId] = self.currentWith(status: .inProgress, progress: progress.fractionCompleted, base: current)
                        self.publishTransfers()
                    }
                }
        }
    }

    nonisolated func session(
        _ session: MCSession,
        didFinishReceivingResourceWithName resourceName: String,
        fromPeer peerID: MCPeerID,
        at localURL: URL?,
        withError error: Error?
    ) {
        Task { @MainActor in
            let transferId = incomingTransferByResourceName.removeValue(forKey: resourceName)
            guard let transferId, let base = transferById[transferId] else { return }

            if let error {
                transferById[transferId] = currentWith(status: .failed, progress: 1, base: base)
                statusMessage = "Receive failed: \(error.localizedDescription)"
                publishTransfers()
                return
            }

            if
                let localURL,
                (try? persistIncomingResource(name: resourceName, url: localURL)) != nil
            {
                transferById[transferId] = currentWith(status: .completed, progress: 1, base: base)
                statusMessage = "Received \(resourceName) from \(peerID.displayName)."
            } else {
                transferById[transferId] = currentWith(status: .failed, progress: 1, base: base)
                statusMessage = "Received file but failed to save it locally."
            }
            publishTransfers()
            progressObservers[transferId] = nil
        }
    }

    nonisolated func session(
        _ session: MCSession,
        didReceiveCertificate certificate: [Any]?,
        fromPeer peerID: MCPeerID,
        certificateHandler: @escaping (Bool) -> Void
    ) {
        certificateHandler(true)
    }
}
