import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum HTTPMethod: String, Sendable {
    case get = "GET", post = "POST", put = "PUT", delete = "DELETE"
}

/// A transport-neutral request. The app converts it to `URLRequest`.
public struct HTTPRequest: Sendable, Equatable {
    public var method: HTTPMethod
    public var url: URL
    public var headers: [String: String]
    public var body: Data?

    public init(method: HTTPMethod, url: URL, headers: [String: String] = [:], body: Data? = nil) {
        self.method = method
        self.url = url
        self.headers = headers
        self.body = body
    }

    /// Case-insensitive header lookup.
    public func header(_ name: String) -> String? {
        headers.first(where: { $0.key.caseInsensitiveCompare(name) == .orderedSame })?.value
    }

    /// The body parsed as ordered JSON, for tests and debugging.
    public var jsonBody: JSONValue? {
        body.flatMap { try? JSONValue.parse($0) }
    }

    /// Builds a `URLRequest` (used by the app's URLSession adapter).
    public func urlRequest(timeout: TimeInterval = 60) -> URLRequest {
        var r = URLRequest(url: url, timeoutInterval: timeout)
        r.httpMethod = method.rawValue
        for (k, v) in headers { r.setValue(v, forHTTPHeaderField: k) }
        r.httpBody = body
        return r
    }
}

public struct HTTPResponse: Sendable, Equatable {
    public var status: Int
    public var headers: [String: String]
    public var body: Data

    public init(status: Int, headers: [String: String] = [:], body: Data = Data()) {
        self.status = status
        self.headers = headers
        self.body = body
    }

    public var isSuccess: Bool { (200..<300).contains(status) }

    public func header(_ name: String) -> String? {
        headers.first(where: { $0.key.caseInsensitiveCompare(name) == .orderedSame })?.value
    }
}

/// Response head plus a body delivered as raw byte chunks (for SSE).
public struct HTTPStreamResponse: Sendable {
    public var status: Int
    public var headers: [String: String]
    public var body: AsyncThrowingStream<Data, any Error>

    public init(status: Int, headers: [String: String] = [:], body: AsyncThrowingStream<Data, any Error>) {
        self.status = status
        self.headers = headers
        self.body = body
    }

    public var isSuccess: Bool { (200..<300).contains(status) }

    public func header(_ name: String) -> String? {
        headers.first(where: { $0.key.caseInsensitiveCompare(name) == .orderedSame })?.value
    }

    /// Drains the body (used to read an error document on non-2xx).
    public func collectBody(limit: Int = 64 * 1024) async throws -> Data {
        var data = Data()
        for try await chunk in body {
            data.append(chunk)
            if data.count >= limit { break }
        }
        return data
    }
}

/// Network transport. The watch app implements it with `URLSession`
/// (`data(for:)` and `bytes(for:)`); tests use `StubHTTPClient`.
/// Implementations must not retry on their own.
public protocol HTTPClient: Sendable {
    func data(for request: HTTPRequest) async throws -> HTTPResponse
    /// Opens a streaming request. The body stream ends when the server closes
    /// the connection; cancelling the consuming task must cancel the request.
    func stream(for request: HTTPRequest) async throws -> HTTPStreamResponse
}
