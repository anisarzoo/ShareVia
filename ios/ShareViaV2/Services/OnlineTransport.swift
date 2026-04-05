import Foundation

protocol OnlineTransportDelegate: AnyObject {
    func onlineTransportDidChange(active: Bool, status: String, roomCode: String?, peers: Int)
}

protocol OnlineTransport {
    var delegate: OnlineTransportDelegate? { get set }
    func connect(displayName: String)
    func disconnect()
    func hostRoom()
    func joinRoom(code: String)
    func leaveRoom()
}

final class RealtimeOnlineTransport: OnlineTransport {
    weak var delegate: OnlineTransportDelegate?
    private var active = false
    private var roomCode: String?

    func connect(displayName: String) {
        active = true
        delegate?.onlineTransportDidChange(
            active: true,
            status: "Realtime connected (scaffold).",
            roomCode: roomCode,
            peers: 0
        )
    }

    func disconnect() {
        active = false
        roomCode = nil
        delegate?.onlineTransportDidChange(
            active: false,
            status: "Realtime disconnected.",
            roomCode: nil,
            peers: 0
        )
    }

    func hostRoom() {
        guard active else { return }
        roomCode = String(Int.random(in: 100000 ... 999999))
        delegate?.onlineTransportDidChange(
            active: true,
            status: "Hosting room \(roomCode ?? "-").",
            roomCode: roomCode,
            peers: 0
        )
    }

    func joinRoom(code: String) {
        guard active else { return }
        roomCode = String(code.prefix(12))
        delegate?.onlineTransportDidChange(
            active: true,
            status: "Joined room \(roomCode ?? "-").",
            roomCode: roomCode,
            peers: 1
        )
    }

    func leaveRoom() {
        roomCode = nil
        delegate?.onlineTransportDidChange(
            active: active,
            status: "Left room.",
            roomCode: nil,
            peers: 0
        )
    }
}
