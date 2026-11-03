import Foundation

/// Composes an ordinary HTTPS webhook for an ntfy topic. Delivery stays the webhook sender; the address is a credential.
public enum NtfyPreset {
    public static let defaultServer = "https://ntfy.sh"
    public static func url(server: String?, topic: String?, token: String?) -> String? {
        let base = (server?.trimmingCharacters(in: .whitespacesAndNewlines)).flatMap { $0.isEmpty ? nil : $0 } ?? defaultServer
        let cleanTopic = topic?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let cleanToken = token?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard cleanTopic.count <= 64, cleanTopic.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "_" || $0 == "-" }),
              !cleanTopic.isEmpty else { return nil }
        if !cleanToken.isEmpty {
            guard cleanToken.count <= 64, cleanToken.hasPrefix("tk_"),
                  cleanToken.dropFirst(3).allSatisfy({ $0.isLetter || $0.isNumber }) else { return nil }
        }
        guard let components = URLComponents(string: base.trimmingCharacters(in: CharacterSet(charactersIn: "/"))),
              components.scheme?.lowercased() == "https",
              let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil,
              components.path.isEmpty || components.path == "/" else { return nil }
        var composed = URLComponents()
        composed.scheme = "https"
        composed.host = host
        composed.port = components.port
        composed.path = "/" + cleanTopic
        if !cleanToken.isEmpty {
            let encoded = Data(("Bearer " + cleanToken).utf8).base64EncodedString()
                .replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
            composed.queryItems = [URLQueryItem(name: "auth", value: encoded)]
        }
        guard let value = composed.string, WebhookAddress.isValid(value) else { return nil }
        return value
    }
}

public enum WebhookAddress {
    /// HTTPS only. A query is allowed solely as one base64url `auth` parameter used by the ntfy preset.
    public static func isValid(_ value: String) -> Bool {
        guard value.utf8.count <= 1024, !value.contains(where: \.isWhitespace),
              let url = URLComponents(string: value), url.scheme?.lowercased() == "https",
              let host = url.host, !host.isEmpty, url.user == nil, url.password == nil,
              url.fragment == nil else { return false }
        guard let items = url.queryItems else { return url.query == nil }
        guard items.count == 1, items[0].name == "auth", let token = items[0].value, !token.isEmpty, token.count <= 256,
              token.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }) else { return false }
        return true
    }
}
