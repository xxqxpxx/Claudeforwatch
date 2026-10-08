import Foundation
import Testing
@testable import ClaudeWatchKit

@Suite struct ThreadStoreTests {
    @Test func threadTitleAndTurns() {
        var t = ChatThread(createdAt: 1)
        #expect(t.displayTitle == "New chat")
        t.appendUser("What is the tallest mountain in the solar system, roughly?", at: 2)
        t.appendAssistant("Olympus Mons on Mars.", stopReason: "end_turn", at: 3)
        #expect(t.title == "What is the tallest mountain in the sola")
        #expect(t.title.count == 40)
        #expect(t.updatedAt == 3)
        #expect(t.turns.map(\.role) == [.user, .assistant])
    }

    @Test func threadJSONShape() throws {
        var t = ChatThread(id: "t1", createdAt: 10, model: "claude-haiku-5-5")
        t.appendUser("Hi", at: 11)
        let json = try JSONValue.parse(try JSONEncoder().encode(t))
        #expect(json == (try JSONValue.parse(#"{"id":"t1","title":"Hi","createdAt":10,"updatedAt":11,"model":"claude-haiku-5-5","messages":[{"role":"user","text":"Hi","at":11,"stopReason":null}]}"#)))
    }

    @Test func inMemoryStore() async throws {
        let store = InMemoryThreadStore()
        try await store.save(ChatThread(id: "a", createdAt: 1, updatedAt: 1))
        try await store.save(ChatThread(id: "b", createdAt: 2, updatedAt: 5))
        #expect(try await store.list().map(\.id) == ["b", "a"])
        try await store.delete(id: "b")
        #expect(try await store.list().map(\.id) == ["a"])
    }

    @Test func jsonFileStoreRoundTripAndCap() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("cfw-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = JSONFileThreadStore(directory: dir, maxThreads: 3)
        for i in 0..<5 {
            var t = ChatThread(id: "t\(i)", createdAt: Int64(i))
            t.appendUser("Question \(i)", at: Int64(i))
            try await store.save(t)
        }
        #expect(try await store.list().map(\.id) == ["t4", "t3", "t2"])

        let reopened = JSONFileThreadStore(directory: dir, maxThreads: 3)
        let t3 = try #require(try await reopened.get(id: "t3"))
        #expect(t3.messages.first?.text == "Question 3")
        try await reopened.delete(id: "t3")
        #expect(try await JSONFileThreadStore(directory: dir).list().map(\.id) == ["t4", "t2"])
    }

    @Test func corruptFileStartsEmpty() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("cfw-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data("not json".utf8).write(to: dir.appendingPathComponent("threads.json"))
        #expect(try await JSONFileThreadStore(directory: dir).list().isEmpty)
    }
}

@Suite struct JSONValueTests {
    @Test func preservesKeyOrderAndRoundTrips() throws {
        let text = #"{"z":1,"a":[true,false,null],"m":{"y":"s","b":-2.5e3},"u":"é😀\n\"q\""}"#
        let v = try JSONValue.parse(text)
        #expect(v.objectValue?.keys == ["z", "a", "m", "u"])
        #expect(v["m"]?.objectValue?.keys == ["y", "b"])
        #expect(v["u"]?.stringValue == "é😀\n\"q\"")
        #expect(v["m"]?["b"]?.doubleValue == -2500)
        #expect(try JSONValue.parse(v.serialized()) == v)
        #expect(v.serialized().hasPrefix(#"{"z":1,"a":[true,false,null],"m":{"y":"s","b":-2500"#))
    }

    @Test func rejectsMalformed() {
        for bad in ["", "{", #"{"a" 1}"#, "[1,]", "tru", #""unterminated"#, "{} x"] {
            #expect(throws: JSONParseError.self) { try JSONValue.parse(bad) }
        }
    }

    @Test func codableBridge() throws {
        let v = try JSONValue.parse(#"{"b":[1,2.5,"x"],"a":null}"#)
        let data = try JSONEncoder().encode(v)
        #expect(try JSONDecoder().decode(JSONValue.self, from: data) == v)
        #expect(JSONValue.string("12").intValue == 12)
        #expect(JSONValue.number(3).intValue == 3)
        #expect(JSONValue.number(3.5).intValue == nil)
    }
}

@Suite struct SharedStateTests {
    @Test func snapshotAndPendingAskRoundTrip() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("cfw-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let snap = WidgetSnapshot(needsActionCount: 2, usagePercent: 37, signedIn: true, updatedAt: 5)
        try SharedJSONFile.write(snap, named: WidgetSnapshot.fileName, in: dir)
        #expect(SharedJSONFile.read(WidgetSnapshot.self, named: WidgetSnapshot.fileName, in: dir) == snap)

        let ask = PendingAsk(text: "What's the weather on Mars?", createdAt: 1_760_000_000_000)
        try SharedJSONFile.write(ask, named: PendingAsk.fileName, in: dir)
        #expect(SharedJSONFile.take(PendingAsk.self, named: PendingAsk.fileName, in: dir) == ask)
        #expect(SharedJSONFile.take(PendingAsk.self, named: PendingAsk.fileName, in: dir) == nil)
        #expect(ask.isFresh(now: Date(timeIntervalSince1970: 1_760_000_000 + 599)))
        #expect(!ask.isFresh(now: Date(timeIntervalSince1970: 1_760_000_000 + 601)))
    }
}
