import ClaudeWatchKit
import Foundation
import Security

/// Generic-password Keychain item, this device only, never synced
/// (docs/PROTOCOL.md §1.1). Values are never logged.
struct KeychainItem: Sendable {
    let service: String
    let account: String

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: kCFBooleanFalse as Any,
        ]
    }

    func read() throws -> Data? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        switch status {
        case errSecSuccess: return result as? Data
        case errSecItemNotFound: return nil
        default: throw KeychainError(status: status)
        }
    }

    func write(_ data: Data) throws {
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemUpdate(baseQuery as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var add = baseQuery
            for (k, v) in attributes { add[k] = v }
            let addStatus = SecItemAdd(add as CFDictionary, nil)
            guard addStatus == errSecSuccess else { throw KeychainError(status: addStatus) }
        } else if status != errSecSuccess {
            throw KeychainError(status: status)
        }
    }

    func delete() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw KeychainError(status: status) }
    }
}

struct KeychainError: Error, Sendable, CustomStringConvertible {
    let status: OSStatus
    var description: String { "Keychain error \(status)" }
}

/// `TokenStore` backed by the Keychain item `com.claudeforwatch.credentials`.
struct KeychainTokenStore: TokenStore {
    static let service = "com.claudeforwatch.credentials"
    let item = KeychainItem(service: KeychainTokenStore.service, account: "default")

    func load() async throws -> Credentials? {
        guard let data = try item.read() else { return nil }
        do {
            return try JSONDecoder().decode(Credentials.self, from: data)
        } catch {
            // Unreadable record: wipe it and ask the user to sign in again.
            try? item.delete()
            return nil
        }
    }

    func save(_ credentials: Credentials) async throws {
        try item.write(try JSONEncoder().encode(credentials))
    }

    func clear() async throws {
        try item.delete()
    }
}

/// The optional routine token (PROTOCOL §6) is a secret too.
struct RoutineTokenStore: Sendable {
    let item = KeychainItem(service: KeychainTokenStore.service, account: "routine-token")

    func load() -> String? {
        (try? item.read()).flatMap { $0 }.map { String(decoding: $0, as: UTF8.self) }
    }

    func save(_ token: String?) {
        if let token, !token.isEmpty { try? item.write(Data(token.utf8)) } else { try? item.delete() }
    }
}
