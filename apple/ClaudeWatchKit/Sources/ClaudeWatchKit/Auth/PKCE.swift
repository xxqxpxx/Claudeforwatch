import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

/// PKCE material for one sign-in attempt, docs/PROTOCOL.md §2.
///
/// `verifier` and `state` are base64url(32 random bytes) = 43 characters;
/// `challenge` is base64url(SHA-256(verifier)). The pair lives in memory only
/// and expires after 10 minutes.
public struct PKCE: Sendable, Equatable {
    public static let lifetime: TimeInterval = 10 * 60

    public let verifier: String
    public let challenge: String
    public let state: String
    public let createdAt: Date

    public init(verifier: String, state: String, createdAt: Date = Date()) {
        self.verifier = verifier
        self.challenge = PKCE.challenge(for: verifier)
        self.state = state
        self.createdAt = createdAt
    }

    /// Fresh random verifier and state from the system CSPRNG.
    public static func generate(now: Date = Date()) -> PKCE {
        var rng = SystemRandomNumberGenerator()
        return generate(using: &rng, now: now)
    }

    public static func generate<R: RandomNumberGenerator>(using rng: inout R, now: Date = Date()) -> PKCE {
        PKCE(verifier: randomToken(using: &rng), state: randomToken(using: &rng), createdAt: now)
    }

    public static func challenge(for verifier: String) -> String {
        Base64URL.encode(SHA256.hash(data: Data(verifier.utf8)))
    }

    public func isExpired(now: Date = Date()) -> Bool {
        now.timeIntervalSince(createdAt) >= PKCE.lifetime
    }

    static func randomToken<R: RandomNumberGenerator>(using rng: inout R) -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        for i in bytes.indices { bytes[i] = UInt8.random(in: .min ... .max, using: &rng) }
        return Base64URL.encode(bytes)
    }
}

extension PKCE: CustomStringConvertible {
    public var description: String { "PKCE(<redacted>, createdAt: \(createdAt))" }
}
