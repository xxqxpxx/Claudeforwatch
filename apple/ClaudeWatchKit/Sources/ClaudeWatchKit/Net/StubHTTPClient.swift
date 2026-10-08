import Foundation

/// A scripted `HTTPClient` for tests and SwiftUI previews.
///
/// Responses are produced by a handler that sees each request; every request
/// is recorded. Streaming bodies are split into `chunkSize`-byte chunks so the
/// SSE path is exercised with arbitrary split points.
public actor StubHTTPClient: HTTPClient {
    public struct Reply: Sendable {
        public var status: Int
        public var headers: [String: String]
        public var body: Data
        /// Simulated latency before the reply, in nanoseconds.
        public var delayNanos: UInt64

        public init(status: Int = 200, headers: [String: String] = [:], body: Data = Data(), delayNanos: UInt64 = 0) {
            self.status = status
            self.headers = headers
            self.body = body
            self.delayNanos = delayNanos
        }

        public init(status: Int = 200, headers: [String: String] = [:], text: String, delayNanos: UInt64 = 0) {
            self.init(status: status, headers: headers, body: Data(text.utf8), delayNanos: delayNanos)
        }
    }

    public typealias Handler = @Sendable (HTTPRequest) async throws -> Reply

    private let handler: Handler
    private let chunkSize: Int
    public private(set) var requests: [HTTPRequest] = []

    public init(chunkSize: Int = 7, handler: @escaping Handler) {
        self.chunkSize = max(1, chunkSize)
        self.handler = handler
    }

    /// Replies from a fixed queue in order; extra requests get a 599.
    public init(chunkSize: Int = 7, queue: [Reply]) {
        let box = ReplyQueue(queue)
        self.init(chunkSize: chunkSize) { _ in await box.next() }
    }

    public func data(for request: HTTPRequest) async throws -> HTTPResponse {
        requests.append(request)
        let reply = try await handler(request)
        if reply.delayNanos > 0 { try await Task.sleep(nanoseconds: reply.delayNanos) }
        return HTTPResponse(status: reply.status, headers: reply.headers, body: reply.body)
    }

    public func stream(for request: HTTPRequest) async throws -> HTTPStreamResponse {
        requests.append(request)
        let reply = try await handler(request)
        if reply.delayNanos > 0 { try await Task.sleep(nanoseconds: reply.delayNanos) }
        let bytes = Array(reply.body)
        let size = chunkSize
        let body = AsyncThrowingStream<Data, any Error> { continuation in
            var i = 0
            while i < bytes.count {
                let end = min(i + size, bytes.count)
                continuation.yield(Data(bytes[i..<end]))
                i = end
            }
            continuation.finish()
        }
        return HTTPStreamResponse(status: reply.status, headers: reply.headers, body: body)
    }

    public var requestCount: Int { requests.count }
}

private actor ReplyQueue {
    var replies: [StubHTTPClient.Reply]
    init(_ replies: [StubHTTPClient.Reply]) { self.replies = replies }
    func next() -> StubHTTPClient.Reply {
        replies.isEmpty ? StubHTTPClient.Reply(status: 599, text: "stub queue exhausted") : replies.removeFirst()
    }
}
