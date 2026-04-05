import SwiftUI

struct AppShellView: View {
    @ObservedObject var viewModel: AppShellViewModel

    var body: some View {
        NavigationSplitView {
            List(AppSection.allCases, selection: $viewModel.selectedSection) { section in
                Text(section.title)
                    .tag(section)
            }
            .navigationTitle("ShareVia")
        } detail: {
            switch viewModel.selectedSection ?? .home {
            case .home:
                homeView
            case .devices:
                placeholderView("Device graph and linked devices appear here.")
            case .profile:
                profileView
            case .history:
                placeholderView("Transfer history module will be added in V2 integration.")
            case .settings:
                placeholderView("Realtime, fallback, and account settings.")
            case .ecosystem:
                placeholderView("Web + extension + desktop continuity controls.")
            case .diagnostics:
                placeholderView("Transport telemetry and diagnostics.")
            case .tools:
                placeholderView("Optional tools live here to keep home minimal.")
            }
        }
    }

    private var homeView: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Quick Connect")
                    .font(.title3)
                    .fontWeight(.semibold)

                HStack(spacing: 10) {
                    Button("Offline") { viewModel.selectMode(.offline) }
                        .buttonStyle(.borderedProminent)
                    Button("Online") { viewModel.selectMode(.online) }
                        .buttonStyle(.borderedProminent)
                }

                if viewModel.transportState.mode == .offline {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Offline Nearby")
                            .font(.headline)
                        Text(viewModel.transportState.offlineStatus)
                            .foregroundStyle(.secondary)
                        HStack {
                            Button("Start Nearby") { viewModel.startOffline() }
                                .buttonStyle(.borderedProminent)
                            Button("Stop") { viewModel.stopOffline() }
                                .buttonStyle(.bordered)
                        }
                    }
                } else {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Online P2P + Relay Fallback")
                            .font(.headline)
                        Text(viewModel.transportState.onlineStatus)
                            .foregroundStyle(.secondary)
                        HStack {
                            Button("Connect Hub") { viewModel.startOnline() }
                                .buttonStyle(.borderedProminent)
                            Button("Disconnect") { viewModel.stopOnline() }
                                .buttonStyle(.bordered)
                        }
                        HStack {
                            Button("Host Room") { viewModel.hostRoom() }
                                .buttonStyle(.borderedProminent)
                            Button("Leave Room") { viewModel.leaveRoom() }
                                .buttonStyle(.bordered)
                        }
                        TextField("Join code", text: $viewModel.joinCodeDraft)
                            .textFieldStyle(.roundedBorder)
                        Button("Join Room") { viewModel.joinRoom() }
                            .buttonStyle(.borderedProminent)
                        Text("Active room: \(viewModel.transportState.roomCode ?? "None"), peers: \(viewModel.transportState.connectedPeers)")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .padding()
        }
        .navigationTitle("Home")
    }

    private var profileView: some View {
        Form {
            TextField("Display name", text: $viewModel.profileName)
        }
        .navigationTitle("Profile")
    }

    private func placeholderView(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(text)
                .foregroundStyle(.secondary)
            Spacer()
        }
        .padding()
    }
}
