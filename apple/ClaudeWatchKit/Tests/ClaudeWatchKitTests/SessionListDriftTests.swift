import Foundation
import Testing
@testable import ClaudeWatchKit

/// spec/fixtures/sessions_list_drift.json: the shape that broke the list on a real watch.
@Suite struct SessionListDriftTests {
    @Test func objectSummaryAndBadItemDoNotBreakTheList() throws {
        let list = try JSONDecoder().decode(SessionList.self, from: try Fixtures.fixture("sessions_list_drift.json"))
        let expected = try Fixtures.expected("sessions_list_drift.json")
        #expect(Double(list.skipped) == expected["skipped"]?.doubleValue)
        let order = expected["visibleOrder"]?.arrayValue?.compactMap(\.stringValue) ?? []
        #expect(SessionListReducer.reduce(list.data).visible.map(\.id) == order)
        for session in list.data {
            #expect(session.externalMetadata?.postTurnSummary == expected["summaries"]?[session.id]?.stringValue)
        }
        #expect(list.data.first?.externalMetadata?.needsActionText == expected["needsActionText"]?["cse_10Drift"]?.stringValue)
    }

    @Test func stringSummaryStillWorks() {
        let meta = Session.ExternalMetadata(postTurnSummary: "Done.")
        #expect(meta.postTurnSummary == "Done.")
        #expect(meta.needsActionText == nil)
    }
}
