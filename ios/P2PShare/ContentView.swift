import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import UIKit

struct ContentView: View {
    @StateObject private var viewModel = OfflineShareViewModel()
    @State private var selectedSection: AppSection? = .home
    @State private var pickerItem: PhotosPickerItem?
    @State private var isFileImporterPresented = false
    @State private var targetPeerId: String?

    var body: some View {
        NavigationSplitView {
            List(AppSection.allCases, selection: $selectedSection) { section in
                Label(section.title, systemImage: section.systemImage)
                    .tag(section as AppSection?)
            }
            .navigationTitle("ShareVia")
        } detail: {
            switch selectedSection ?? .home {
            case .home:
                homeScreen
            case .profile:
                profileScreen
            case .history:
                historyScreen
            }
        }
        .task {
            viewModel.startIfNeeded()
        }
        .fileImporter(
            isPresented: $isFileImporterPresented,
            allowedContentTypes: [UTType.item],
            allowsMultipleSelection: false
        ) { result in
            guard
                let peerId = targetPeerId,
                case .success(let urls) = result,
                let url = urls.first
            else { return }
            viewModel.sendFile(url: url, toPeerId: peerId)
        }
    }

    private var homeScreen: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                statusCard
                Text("Nearby devices")
                    .font(.headline)
                if viewModel.peers.isEmpty {
                    Text("No nearby devices yet. Keep both phones on this page.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(viewModel.peers) { peer in
                        peerCard(peer)
                    }
                }
                if !viewModel.liveTransfers.isEmpty {
                    Text("Live transfers")
                        .font(.headline)
                        .padding(.top, 6)
                    ForEach(viewModel.liveTransfers) { transfer in
                        transferCard(transfer)
                    }
                }
            }
            .padding()
        }
        .navigationTitle("Nearby Share")
    }

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Offline-first mode")
                .font(.headline)
            Text(viewModel.statusMessage)
                .foregroundStyle(.secondary)
            Text("Works without internet. Flight mode sharing can still work if Bluetooth is enabled.")
                .font(.footnote)
                .foregroundStyle(.secondary)
            HStack(spacing: 10) {
                Button(viewModel.isRunning ? "Restart Nearby" : "Start Nearby") {
                    viewModel.start()
                }
                .buttonStyle(.borderedProminent)
                Button("Stop") {
                    viewModel.stop()
                }
                .buttonStyle(.bordered)
                .disabled(!viewModel.isRunning)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14))
    }

    private func peerCard(_ peer: NativePeer) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                avatarView(imageData: nil, fallbackName: peer.displayName, size: 34)
                VStack(alignment: .leading) {
                    Text(peer.displayName).font(.headline)
                    Text(peer.state.label)
                        .font(.caption)
                        .foregroundStyle(peer.state == .connected ? .green : .secondary)
                }
                Spacer()
            }
            HStack(spacing: 10) {
                Button(peer.state == .connected ? "Connected" : "Pair") {
                    viewModel.invite(peerId: peer.id)
                }
                .buttonStyle(.borderedProminent)
                .disabled(peer.state == .connected)

                Button("Send file") {
                    targetPeerId = peer.id
                    isFileImporterPresented = true
                }
                .buttonStyle(.bordered)
                .disabled(peer.state != .connected)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private func transferCard(_ transfer: NativeTransfer) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(transfer.fileName).font(.headline)
            Text("\(transfer.peerName) - \(transfer.direction.rawValue)")
                .font(.caption)
                .foregroundStyle(.secondary)
            ProgressView(value: transfer.progress)
            Text(transfer.status.rawValue)
                .font(.caption2)
                .foregroundStyle(transfer.status == .failed ? .red : .secondary)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private var profileScreen: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Update your profile so nearby users can identify you quickly.")
                    .foregroundStyle(.secondary)
                HStack(spacing: 14) {
                    avatarView(imageData: viewModel.avatarImageData, fallbackName: viewModel.profileName, size: 68)
                    PhotosPicker(selection: $pickerItem, matching: .images) {
                        Text("Change DP")
                    }
                    .buttonStyle(.bordered)
                }
                TextField("Display name", text: $viewModel.profileName)
                    .textFieldStyle(.roundedBorder)
                Button("Save Profile") {
                    viewModel.saveProfile()
                }
                .buttonStyle(.borderedProminent)
            }
            .padding()
        }
        .navigationTitle("Profile")
        .onChange(of: pickerItem) { _, newItem in
            guard let newItem else { return }
            Task {
                if let data = try? await newItem.loadTransferable(type: Data.self) {
                    viewModel.avatarImageData = data
                    viewModel.saveProfile()
                }
            }
        }
    }

    private var historyScreen: some View {
        List {
            if viewModel.history.isEmpty {
                Text("No transfer history yet.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(viewModel.history) { entry in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(entry.fileName).font(.headline)
                        Text("\(entry.peerName) - \(entry.direction.rawValue)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(entry.timestamp.formatted(date: .abbreviated, time: .shortened))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Activity History")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Clear") {
                    viewModel.clearHistory()
                }
                .disabled(viewModel.history.isEmpty)
            }
        }
    }

    private func avatarView(imageData: Data?, fallbackName: String, size: CGFloat) -> some View {
        Group {
            if
                let data = imageData,
                let image = UIImage(data: data)
            {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Text(String(fallbackName.prefix(1)).uppercased())
                    .font(.headline)
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.blue)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
    }
}
