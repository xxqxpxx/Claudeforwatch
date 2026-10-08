import ClaudeWatchKit
import Foundation

/// `HTTPClient` over `URLSession`. Streams use `bytes(for:)` (watchOS 8+), the
/// only streaming primitive watchOS allows third-party apps (no sockets,
/// no WebSockets, TN3135). Works only while the app is in the foreground; the
/// view models cancel streams on background and resume from the last
/// sequence number (docs/PROTOCOL.md §5.2).
final class URLSessionHTTPClient: HTTPClient {
    static let requestTimeout: TimeInterval = 60

    private let session: URLSession

    init() {
        let config = URLSessionConfiguration.default
        config.waitsForConnectivity = true
        // Idle timeout between packets; a silent stream errors and is reopened.
        config.timeoutIntervalForRequest = Self.requestTimeout
        config.timeoutIntervalForResource = 60 * 60
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.urlCache = nil
        config.httpShouldSetCookies = false
        config.httpCookieAcceptPolicy = .never
        session = URLSession(configuration: config)
    }

    func data(for request: HTTPRequest) async throws -> HTTPResponse {
        let (data, response) = try await session.data(for: request.urlRequest(timeout: Self.requestTimeout))
        let http = response as? HTTPURLResponse
        return HTTPResponse(status: http?.statusCode ?? 0, headers: Self.headers(http), body: data)
    }

    func stream(for request: HTTPRequest) async throws -> HTTPStreamResponse {
        let urlRequest = request.urlRequest(timeout: Self.requestTimeout)
        let session = self.session
        let (head, headContinuation) = AsyncThrowingStream<HTTPResponse, any Error>.makeStream()
        let (body, bodyContinuation) = AsyncThrowingStream<Data, any Error>.makeStream()

        // The byte sequence is created and consumed inside one task, so it never
        // crosses an isolation boundary.
        let task = Task {
            do {
                let (bytes, response) = try await session.bytes(for: urlRequest)
                let http = response as? HTTPURLResponse
                headContinuation.yield(HTTPResponse(status: http?.statusCode ?? 0, headers: Self.headers(http)))
                headContinuation.finish()
                var buffer = Data()
                buffer.reserveCapacity(2048)
                for try await byte in bytes {
                    buffer.append(byte)
                    // Flush on newline so SSE events arrive with no added latency.
                    if byte == 0x0A || buffer.count >= 2048 {
                        bodyContinuation.yield(buffer)
                        buffer.removeAll(keepingCapacity: true)
                    }
                }
                if !buffer.isEmpty { bodyContinuation.yield(buffer) }
                bodyContinuation.finish()
            } catch {
                headContinuation.finish(throwing: error)
                bodyContinuation.finish(throwing: error)
            }
        }
        bodyContinuation.onTermination = { _ in task.cancel() }

        return try await withTaskCancellationHandler {
            var iterator = head.makeAsyncIterator()
            guard let response = try await iterator.next() else { throw CancellationError() }
            return HTTPStreamResponse(status: response.status, headers: response.headers, body: body)
        } onCancel: {
            task.cancel()
        }
    }

    private static func headers(_ response: HTTPURLResponse?) -> [String: String] {
        var out: [String: String] = [:]
        for (k, v) in response?.allHeaderFields ?? [:] {
            if let k = k as? String, let v = v as? String { out[k] = v }
        }
        return out
    }
}
