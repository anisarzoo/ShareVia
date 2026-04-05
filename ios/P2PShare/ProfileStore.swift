import Foundation

final class ProfileStore {
    static let shared = ProfileStore()

    private enum Keys {
        static let name = "sharevia.profile.name"
        static let avatarData = "sharevia.profile.avatar_data"
        static let history = "sharevia.profile.history"
    }

    private let defaults = UserDefaults.standard

    private init() {}

    func loadName() -> String {
        let stored = defaults.string(forKey: Keys.name)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return stored.isEmpty ? "My iPhone" : stored
    }

    func loadAvatarData() -> Data? {
        defaults.data(forKey: Keys.avatarData)
    }

    func saveProfile(name: String, avatarData: Data?) {
        defaults.set(name, forKey: Keys.name)
        defaults.set(avatarData, forKey: Keys.avatarData)
    }

    func loadHistory() -> [TransferHistoryItem] {
        guard let data = defaults.data(forKey: Keys.history) else { return [] }
        let decoded = (try? JSONDecoder().decode([TransferHistoryItem].self, from: data)) ?? []
        return decoded.sorted(by: { $0.timestamp > $1.timestamp })
    }

    func saveHistory(_ history: [TransferHistoryItem]) {
        let bounded = Array(history.prefix(150))
        let data = try? JSONEncoder().encode(bounded)
        defaults.set(data, forKey: Keys.history)
    }
}
