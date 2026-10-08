import Foundation

/// Persistence for `Credentials`. The watch app backs this with the Keychain
/// (`KeychainTokenStore`, docs/PROTOCOL.md §1.1); tests use `InMemoryTokenStore`.
public protocol TokenStore: Sendable {
    func load() async throws -> Credentials?
    func save(_ credentials: Credentials) async throws
    func clear() async throws
}

public actor InMemoryTokenStore: TokenStore {
    private var stored: Credentials?
    public private(set) var saveCount = 0

    public init(_ initial: Credentials? = nil) {
        stored = initial
    }

    public func load() async throws -> Credentials? { stored }

    public func save(_ credentials: Credentials) async throws {
        stored = credentials
        saveCount += 1
    }

    public func clear() async throws { stored = nil }
}
