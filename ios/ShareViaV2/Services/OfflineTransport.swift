import Foundation

protocol OfflineTransportDelegate: AnyObject {
    func offlineTransportDidChange(active: Bool, status: String)
}

protocol OfflineTransport {
    var delegate: OfflineTransportDelegate? { get set }
    func start(displayName: String)
    func stop()
}

final class NearbyOfflineTransport: OfflineTransport {
    weak var delegate: OfflineTransportDelegate?

    func start(displayName: String) {
        // V2 target:
        // Replace this scaffold with Nearby Connections Swift package integration.
        delegate?.offlineTransportDidChange(
            active: true,
            status: "Nearby offline transport started for \(displayName)."
        )
    }

    func stop() {
        delegate?.offlineTransportDidChange(
            active: false,
            status: "Nearby offline transport stopped."
        )
    }
}
