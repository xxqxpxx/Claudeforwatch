import Foundation
import Testing
@testable import ClaudeWatchKit

@Suite struct PKCETests {
    @Test func rfc7636AppendixBVector() {
        #expect(PKCE.challenge(for: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
            == "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    }

    @Test func generatedValuesAreBase64URL43Chars() {
        let allowed = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
        for _ in 0..<20 {
            let p = PKCE.generate()
            #expect(p.verifier.count == 43)
            #expect(p.state.count == 43)
            #expect(p.challenge.count == 43)
            #expect(p.verifier.allSatisfy(allowed.contains))
            #expect(p.state.allSatisfy(allowed.contains))
            #expect(p.challenge.allSatisfy(allowed.contains))
            #expect(p.verifier != p.state)
            #expect(p.challenge == PKCE.challenge(for: p.verifier))
        }
    }

    @Test func distinctAcrossCalls() {
        let set = Set((0..<50).map { _ in PKCE.generate().verifier })
        #expect(set.count == 50)
    }

    @Test func expiresAfterTenMinutes() {
        let t0 = Date(timeIntervalSince1970: 1000)
        let p = PKCE(verifier: "v", state: "s", createdAt: t0)
        #expect(!p.isExpired(now: t0.addingTimeInterval(599)))
        #expect(p.isExpired(now: t0.addingTimeInterval(600)))
    }

    @Test func descriptionIsRedacted() {
        let p = PKCE.generate()
        #expect(!String(describing: p).contains(p.verifier))
        let c = fixtureOAuthCredentials
        #expect(!String(describing: c).contains("sk-ant"))
    }
}
