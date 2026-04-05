import SwiftUI

@main
struct ShareViaV2App: App {
    @StateObject private var viewModel = AppShellViewModel()

    var body: some Scene {
        WindowGroup {
            AppShellView(viewModel: viewModel)
        }
    }
}
