import Foundation
import Testing
@testable import ClaudeWatchKit

/// Golden matrices were generated with the `segno` Python library (same text,
/// version, ECC level and mask; segno patched to skip its extra zero byte when
/// the bit stream is already byte-aligned, which ISO 18004 §7.4.10 does not add). During development the encoder was also
/// cross-checked against segno for all 40 versions × 4 ECC levels × 8 masks.
@Suite struct QRCodeTests {
    func rows(_ q: QRCode) -> [String] {
        (0..<q.size).map { y in String((0..<q.size).map { q[x: $0, y: y] ? "1" : "0" }) }
    }

    @Test func goldenVersion1Medium() throws {
        let q = try QRCode.encode("claude", errorCorrection: .medium, mask: 3)
        #expect(q.version == 1)
        #expect(rows(q) == [
            "111111101111001111111", "100000101001101000001", "101110100111001011101",
            "101110101101101011101", "101110100010001011101", "100000100000001000001",
            "111111101010101111111", "000000001000000000000", "101101110010001001011",
            "001101011000100101001", "011111101010111111011", "111001000100110001001",
            "011010101011010010000", "000000001110010001000", "111111101110011011000",
            "100000101011111001101", "101110100111001100111", "101110101100010001010",
            "101110101011010001100", "100000100111101000001", "111111101001100110100",
        ])
    }

    @Test func goldenVersion2Low() throws {
        let q = try QRCode.encode("https://claude.ai/code", errorCorrection: .low, mask: 5)
        #expect(q.version == 2)
        #expect(rows(q) == [
            "1111111001110010101111111", "1000001000011110101000001", "1011101001110011001011101",
            "1011101010110101101011101", "1011101010101000101011101", "1000001001100100101000001",
            "1111111010101010101111111", "0000000001001010000000000", "1100011101101111000011000",
            "0100000100100111000111110", "1110101111011111010111011", "1010110000110011101001001",
            "0000111001100000101100001", "1101110111101001000100010", "1001001011100011101111011",
            "1001010100101010011101101", "1000101000101111111110100", "0000000010100000100010000",
            "1111111011001100101010001", "1000001010110011100010000", "1011101000010001111110111",
            "1011101000001110111000011", "1011101000100110010001101", "1000001011001100111110001",
            "1111111010011100101001001",
        ])
    }

    @Test func authorizeURLFitsAndPicksSameMaskAsReference() throws {
        let pkce = PKCE(verifier: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", state: "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        let url = OAuthClient(http: StubHTTPClient(queue: [])).authorizeURL(pkce: pkce).absoluteString
        let q = try QRCode.encode(url, errorCorrection: .low)
        // PROTOCOL §2: fits within version 15.
        #expect(q.version <= 15)
        #expect(q.version == 13)
        #expect(q.mask == 2) // segno's automatic choice for the same input
        #expect(q.size == 69)
    }

    @Test func structure() throws {
        for text in ["a", String(repeating: "x", count: 300), String(repeating: "é", count: 500)] {
            let q = try QRCode.encode(text)
            #expect(q.size == q.version * 4 + 17)
            #expect(q.modules.count == q.size * q.size)
            // Finder pattern corners are dark, separators light.
            for (x, y) in [(0, 0), (q.size - 1, 0), (0, q.size - 1)] { #expect(q[x: x, y: y]) }
            #expect(!q[x: 7, y: 7])
            #expect(q[x: 8, y: q.size - 8]) // dark module
            #expect(!q[x: -1, y: 0])
        }
    }

    @Test func errors() {
        #expect(throws: QRCode.EncodeError.dataTooLong) {
            try QRCode.encode(String(repeating: "x", count: 3000))
        }
        #expect(throws: QRCode.EncodeError.dataTooLong) {
            try QRCode.encode(String(repeating: "x", count: 100), maxVersion: 2)
        }
        #expect(throws: QRCode.EncodeError.invalidArgument) { try QRCode.encode("x", mask: 8) }
        #expect(throws: QRCode.EncodeError.invalidArgument) { try QRCode.encode("x", minVersion: 0) }
    }
}
