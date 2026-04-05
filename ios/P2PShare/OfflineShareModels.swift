import Foundation

enum AppSection: String, CaseIterable, Identifiable {
    case home
    case profile
    case history

    var id: String { rawValue }

    var title: String {
        switch self {
        case .home: return "Home"
        case .profile: return "Profile"
        case .history: return "History"
        }
    }

    var systemImage: String {
        switch self {
        case .home: return "house"
        case .profile: return "person.crop.circle"
        case .history: return "clock.arrow.circlepath"
        }
    }
}

enum PeerState: String {
    case discovered = "Discovered"
    case connecting = "Connecting"
    case connected = "Connected"

    var label: String { rawValue }
}

struct NativePeer: Identifiable {
    let id: String
    let displayName: String
    let state: PeerState
    let lastSeen: Date
}

enum TransferDirection: String, Codable {
    case outgoing = "Sent"
    case incoming = "Received"
}

enum TransferStatus: String {
    case queued = "Queued"
    case inProgress = "In progress"
    case completed = "Completed"
    case failed = "Failed"
}

struct NativeTransfer: Identifiable {
    let id: UUID
    let peerId: String
    let peerName: String
    let fileName: String
    let direction: TransferDirection
    let status: TransferStatus
    let progress: Double
    let timestamp: Date
}

struct TransferHistoryItem: Identifiable, Codable {
    let id: UUID
    let fileName: String
    let peerName: String
    let direction: TransferDirection
    let timestamp: Date
}
