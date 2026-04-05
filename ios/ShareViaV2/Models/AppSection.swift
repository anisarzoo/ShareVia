import Foundation

enum ShareMode: String, CaseIterable {
    case offline
    case online
}

enum AppSection: String, CaseIterable, Identifiable {
    case home
    case devices
    case profile
    case history
    case settings
    case ecosystem
    case diagnostics
    case tools

    var id: String { rawValue }

    var title: String {
        switch self {
        case .home: return "Home"
        case .devices: return "Devices"
        case .profile: return "Profile"
        case .history: return "History"
        case .settings: return "Settings"
        case .ecosystem: return "Ecosystem"
        case .diagnostics: return "Diagnostics"
        case .tools: return "Tools"
        }
    }
}

struct AppTransportState {
    var mode: ShareMode = .offline
    var offlineActive = false
    var onlineActive = false
    var offlineStatus = "Offline mode ready."
    var onlineStatus = "Online mode ready."
    var roomCode: String?
    var connectedPeers = 0
    var supportsNfc = false
}
