import Foundation

/// Adds auth headers and implements "refresh once on 401" (docs/PLAN.md §4).
/// A 401 is never billed, so re-sending after a refresh is not an
/// auto-retry of a completion.
public struct AuthorizedTransport: Sendable {
    public let http: any HTTPClient
    public let auth: AuthProvider

    public init(http: any HTTPClient, auth: AuthProvider) {
        self.http = http
        self.auth = auth
    }

    func request(
        _ endpoint: Endpoint,
        method: HTTPMethod,
        url: URL,
        body: Data?,
        extraHeaders: [String: String] = [:]
    ) async throws -> HTTPRequest {
        var headers = try await auth.authHeaders(for: endpoint)
        for (k, v) in extraHeaders { headers[k] = v }
        return HTTPRequest(method: method, url: url, headers: headers, body: body)
    }

    /// Sends and returns a 2xx response, or throws `APIError`.
    /// `acceptStatuses` lets callers treat e.g. 409 as success.
    public func send(
        _ endpoint: Endpoint,
        method: HTTPMethod,
        url: URL,
        body: Data? = nil,
        extraHeaders: [String: String] = [:],
        acceptStatuses: Set<Int> = []
    ) async throws -> HTTPResponse {
        var attempt = 0
        while true {
            let req = try await request(endpoint, method: method, url: url, body: body, extraHeaders: extraHeaders)
            let response = try await http.data(for: req)
            if response.isSuccess || acceptStatuses.contains(response.status) { return response }
            if response.status == 401, attempt == 0, await auth.mode == .claudeAccount {
                attempt += 1
                try await auth.handleUnauthorized(rejectedAuthorization: req.header("Authorization"))
                continue
            }
            throw APIError.from(status: response.status, headers: response.headers, body: response.body)
        }
    }

    /// Opens a stream and returns it once the server answered 2xx.
    public func openStream(
        _ endpoint: Endpoint,
        method: HTTPMethod,
        url: URL,
        body: Data? = nil,
        extraHeaders: [String: String] = [:]
    ) async throws -> HTTPStreamResponse {
        var attempt = 0
        while true {
            let req = try await request(endpoint, method: method, url: url, body: body, extraHeaders: extraHeaders)
            let response = try await http.stream(for: req)
            if response.isSuccess { return response }
            let errorBody = (try? await response.collectBody()) ?? Data()
            if response.status == 401, attempt == 0, await auth.mode == .claudeAccount {
                attempt += 1
                try await auth.handleUnauthorized(rejectedAuthorization: req.header("Authorization"))
                continue
            }
            throw APIError.from(status: response.status, headers: response.headers, body: errorBody)
        }
    }
}
