import Foundation

enum Base64URL {
    /// base64url without padding (RFC 4648 §5), as PKCE requires.
    static func encode(_ data: some Sequence<UInt8>) -> String {
        Data(data).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

extension String {
    /// Percent-encodes everything except RFC 3986 unreserved characters, so a
    /// query value round-trips the same on every platform (spaces become %20).
    var strictlyPercentEncoded: String {
        var allowed = CharacterSet()
        allowed.insert(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return addingPercentEncoding(withAllowedCharacters: allowed) ?? self
    }
}
