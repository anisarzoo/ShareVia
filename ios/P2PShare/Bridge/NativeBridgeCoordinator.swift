import Foundation
import WebKit

final class NativeBridgeCoordinator: NSObject, WKScriptMessageHandler {
    weak var webView: WKWebView?

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "NativeP2PBridge" else {
            return
        }

        let action = extractAction(from: message.body)

        switch action {
        case "startBluetoothPairing":
            sendInfo("Bluetooth pairing requested from web layer.")
            sendPairingCode()
        case "startNfcPairing":
            sendInfo("NFC pairing requested from web layer.")
            sendPairingCode()
        case "startLocationPairing":
            sendInfo("Location-assisted pairing requested from web layer.")
            sendPairingCode()
        default:
            sendInfo("Unknown native action: \(action ?? "none")")
        }
    }

    private func extractAction(from payload: Any) -> String? {
        if let dictionary = payload as? [String: Any], let action = dictionary["action"] as? String {
            return action
        }

        if let raw = payload as? String,
           let data = raw.data(using: .utf8),
           let dictionary = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let action = dictionary["action"] as? String {
            return action
        }

        return nil
    }

    private func sendPairingCode() {
        let code = String(Int.random(in: 100000...999999))
        sendPayload(["type": "pairing-code", "code": code])
    }

    private func sendInfo(_ message: String) {
        sendPayload(["type": "info", "message": message])
    }

    private func sendPayload(_ payload: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let json = String(data: data, encoding: .utf8) else {
            return
        }

        DispatchQueue.main.async { [weak self] in
            self?.webView?.evaluateJavaScript("window.handleNativeBridgeMessage(\(json));")
        }
    }
}
