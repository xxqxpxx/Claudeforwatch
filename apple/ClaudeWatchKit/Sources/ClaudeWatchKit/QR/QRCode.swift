import Foundation

/// A QR Code Model 2 encoder (byte mode), Foundation-only.
///
/// Why not Core Image's `CIQRCodeGenerator`: Core Image is not available on
/// watchOS, and the watch must render the OAuth authorize URL as a QR code
/// (docs/PROTOCOL.md §2) with no phone app. This follows ISO/IEC 18004 and the
/// structure of Project Nayuki's reference generator (MIT): pick the smallest
/// version that fits, add Reed–Solomon ECC, interleave blocks, place modules,
/// and choose the mask with the lowest penalty. The app draws `modules` with a
/// SwiftUI `Canvas`; add a 4-module quiet zone around it.
public struct QRCode: Sendable, Equatable {
    public enum ErrorCorrection: Int, Sendable, CaseIterable {
        case low = 0, medium, quartile, high

        /// The two format bits for this level (L=01, M=00, Q=11, H=10).
        var formatBits: Int {
            switch self {
            case .low: return 1
            case .medium: return 0
            case .quartile: return 3
            case .high: return 2
            }
        }
    }

    public enum EncodeError: Error, Sendable, Equatable {
        case dataTooLong
        case invalidArgument
    }

    public let version: Int
    public let size: Int
    public let errorCorrection: ErrorCorrection
    public let mask: Int
    /// Row-major, `true` = dark. Index `y * size + x`.
    public let modules: [Bool]

    public subscript(x x: Int, y y: Int) -> Bool {
        guard x >= 0, y >= 0, x < size, y < size else { return false }
        return modules[y * size + x]
    }

    /// Encodes UTF-8 text. `mask == nil` chooses the best mask automatically.
    public static func encode(
        _ text: String,
        errorCorrection: ErrorCorrection = .low,
        minVersion: Int = 1,
        maxVersion: Int = 40,
        mask: Int? = nil
    ) throws -> QRCode {
        try encode(bytes: Array(text.utf8), errorCorrection: errorCorrection, minVersion: minVersion, maxVersion: maxVersion, mask: mask)
    }

    public static func encode(
        bytes: [UInt8],
        errorCorrection ecl: ErrorCorrection = .low,
        minVersion: Int = 1,
        maxVersion: Int = 40,
        mask: Int? = nil
    ) throws -> QRCode {
        guard (1...40).contains(minVersion), (minVersion...40).contains(maxVersion),
              mask.map({ (0...7).contains($0) }) ?? true else { throw EncodeError.invalidArgument }

        // Choose the smallest version that fits.
        var version = minVersion
        var usedBits = 0
        while true {
            let capacityBits = numDataCodewords(version, ecl) * 8
            let countBits = version <= 9 ? 8 : 16
            usedBits = 4 + countBits + bytes.count * 8
            if bytes.count < (1 << countBits), usedBits <= capacityBits { break }
            if version >= maxVersion { throw EncodeError.dataTooLong }
            version += 1
        }

        // Bit stream: mode 0100 (byte), character count, data, terminator, padding.
        var bits = BitBuffer()
        bits.append(0x4, count: 4)
        bits.append(bytes.count, count: version <= 9 ? 8 : 16)
        for b in bytes { bits.append(Int(b), count: 8) }
        let capacityBits = numDataCodewords(version, ecl) * 8
        bits.append(0, count: min(4, capacityBits - bits.count))
        bits.append(0, count: (8 - bits.count % 8) % 8)
        var padByte = 0xEC
        while bits.count < capacityBits {
            bits.append(padByte, count: 8)
            padByte ^= 0xEC ^ 0x11
        }
        let dataCodewords = bits.bytes()

        var matrix = Matrix(version: version)
        matrix.drawFunctionPatterns(ecl: ecl)
        let allCodewords = addEccAndInterleave(dataCodewords, version: version, ecl: ecl)
        matrix.drawCodewords(allCodewords)

        let chosenMask: Int
        if let mask {
            chosenMask = mask
        } else {
            var best = 0
            var minPenalty = Int.max
            for m in 0..<8 {
                matrix.applyMask(m)
                matrix.drawFormatBits(ecl: ecl, mask: m)
                let penalty = matrix.penaltyScore()
                if penalty < minPenalty {
                    minPenalty = penalty
                    best = m
                }
                matrix.applyMask(m) // XOR again to undo
            }
            chosenMask = best
        }
        matrix.applyMask(chosenMask)
        matrix.drawFormatBits(ecl: ecl, mask: chosenMask)

        return QRCode(version: version, size: matrix.size, errorCorrection: ecl, mask: chosenMask, modules: matrix.modules)
    }

    // MARK: Tables (index [ecl][version])

    static let eccCodewordsPerBlock: [[Int]] = [
        [-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
        [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28],
        [-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
        [-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
    ]

    static let numErrorCorrectionBlocks: [[Int]] = [
        [-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25],
        [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49],
        [-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68],
        [-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81],
    ]

    static func numRawDataModules(_ ver: Int) -> Int {
        var result = (16 * ver + 128) * ver + 64
        if ver >= 2 {
            let numAlign = ver / 7 + 2
            result -= (25 * numAlign - 10) * numAlign - 55
            if ver >= 7 { result -= 36 }
        }
        return result
    }

    static func numDataCodewords(_ ver: Int, _ ecl: ErrorCorrection) -> Int {
        numRawDataModules(ver) / 8
            - eccCodewordsPerBlock[ecl.rawValue][ver] * numErrorCorrectionBlocks[ecl.rawValue][ver]
    }

    static func addEccAndInterleave(_ data: [UInt8], version: Int, ecl: ErrorCorrection) -> [UInt8] {
        let numBlocks = numErrorCorrectionBlocks[ecl.rawValue][version]
        let blockEccLen = eccCodewordsPerBlock[ecl.rawValue][version]
        let rawCodewords = numRawDataModules(version) / 8
        let numShortBlocks = numBlocks - rawCodewords % numBlocks
        let shortBlockLen = rawCodewords / numBlocks

        let divisor = ReedSolomon.divisor(degree: blockEccLen)
        var blocks: [[UInt8]] = []
        var k = 0
        for i in 0..<numBlocks {
            let len = shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1)
            var block = Array(data[k..<k + len])
            k += len
            let ecc = ReedSolomon.remainder(block, divisor: divisor)
            if i < numShortBlocks { block.append(0) }
            blocks.append(block + ecc)
        }
        var result: [UInt8] = []
        result.reserveCapacity(rawCodewords)
        for i in 0..<blocks[0].count {
            for j in 0..<blocks.count where i != shortBlockLen - blockEccLen || j >= numShortBlocks {
                result.append(blocks[j][i])
            }
        }
        return result
    }
}

// MARK: - Internals

private struct BitBuffer {
    private(set) var bits: [Bool] = []
    var count: Int { bits.count }

    mutating func append(_ value: Int, count: Int) {
        guard count > 0 else { return }
        for i in stride(from: count - 1, through: 0, by: -1) { bits.append((value >> i) & 1 == 1) }
    }

    func bytes() -> [UInt8] {
        var out = [UInt8](repeating: 0, count: bits.count / 8)
        for (i, bit) in bits.enumerated() where bit { out[i >> 3] |= UInt8(1 << (7 - (i & 7))) }
        return out
    }
}

enum ReedSolomon {
    static func multiply(_ x: Int, _ y: Int) -> Int {
        var z = 0
        for i in stride(from: 7, through: 0, by: -1) {
            z = (z << 1) ^ ((z >> 7) * 0x11D)
            z ^= ((y >> i) & 1) * x
        }
        return z & 0xFF
    }

    static func divisor(degree: Int) -> [Int] {
        var result = [Int](repeating: 0, count: degree)
        result[degree - 1] = 1
        var root = 1
        for _ in 0..<degree {
            for j in 0..<degree {
                result[j] = multiply(result[j], root)
                if j + 1 < degree { result[j] ^= result[j + 1] }
            }
            root = multiply(root, 0x02)
        }
        return result
    }

    static func remainder(_ data: [UInt8], divisor: [Int]) -> [UInt8] {
        var result = [Int](repeating: 0, count: divisor.count)
        for b in data {
            let factor = Int(b) ^ result.removeFirst()
            result.append(0)
            for i in result.indices { result[i] ^= multiply(divisor[i], factor) }
        }
        return result.map { UInt8($0) }
    }
}

private struct Matrix {
    let version: Int
    let size: Int
    var modules: [Bool]
    var isFunction: [Bool]

    init(version: Int) {
        self.version = version
        size = version * 4 + 17
        modules = [Bool](repeating: false, count: size * size)
        isFunction = [Bool](repeating: false, count: size * size)
    }

    func get(_ x: Int, _ y: Int) -> Bool { modules[y * size + x] }

    mutating func setFunction(_ x: Int, _ y: Int, _ dark: Bool) {
        modules[y * size + x] = dark
        isFunction[y * size + x] = true
    }

    mutating func drawFunctionPatterns(ecl: QRCode.ErrorCorrection) {
        for i in 0..<size {
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)
        let positions = alignmentPositions()
        let n = positions.count
        for i in 0..<n {
            for j in 0..<n where !((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) {
                drawAlignment(positions[i], positions[j])
            }
        }
        drawFormatBits(ecl: ecl, mask: 0) // reserve; overwritten later
        drawVersion()
    }

    mutating func drawFinder(_ x: Int, _ y: Int) {
        for dy in -4...4 {
            for dx in -4...4 {
                let dist = max(abs(dx), abs(dy))
                let xx = x + dx, yy = y + dy
                if xx >= 0, xx < size, yy >= 0, yy < size { setFunction(xx, yy, dist != 2 && dist != 4) }
            }
        }
    }

    mutating func drawAlignment(_ x: Int, _ y: Int) {
        for dy in -2...2 {
            for dx in -2...2 { setFunction(x + dx, y + dy, max(abs(dx), abs(dy)) != 1) }
        }
    }

    func alignmentPositions() -> [Int] {
        guard version > 1 else { return [] }
        let numAlign = version / 7 + 2
        let step = (version * 8 + numAlign * 3 + 5) / (numAlign * 4 - 4) * 2
        var result = [Int](repeating: 0, count: numAlign)
        result[0] = 6
        var pos = size - 7
        for i in stride(from: numAlign - 1, through: 1, by: -1) {
            result[i] = pos
            pos -= step
        }
        return result
    }

    mutating func drawFormatBits(ecl: QRCode.ErrorCorrection, mask: Int) {
        let data = ecl.formatBits << 3 | mask
        var rem = data
        for _ in 0..<10 { rem = (rem << 1) ^ ((rem >> 9) * 0x537) }
        let bits = (data << 10 | rem) ^ 0x5412
        func bit(_ i: Int) -> Bool { (bits >> i) & 1 == 1 }

        for i in 0...5 { setFunction(8, i, bit(i)) }
        setFunction(8, 7, bit(6))
        setFunction(8, 8, bit(7))
        setFunction(7, 8, bit(8))
        for i in 9..<15 { setFunction(14 - i, 8, bit(i)) }

        for i in 0..<8 { setFunction(size - 1 - i, 8, bit(i)) }
        for i in 8..<15 { setFunction(8, size - 15 + i, bit(i)) }
        setFunction(8, size - 8, true) // dark module
    }

    mutating func drawVersion() {
        guard version >= 7 else { return }
        var rem = version
        for _ in 0..<12 { rem = (rem << 1) ^ ((rem >> 11) * 0x1F25) }
        let bits = version << 12 | rem
        for i in 0..<18 {
            let dark = (bits >> i) & 1 == 1
            let a = size - 11 + i % 3, b = i / 3
            setFunction(a, b, dark)
            setFunction(b, a, dark)
        }
    }

    mutating func drawCodewords(_ data: [UInt8]) {
        var i = 0
        var right = size - 1
        while right >= 1 {
            if right == 6 { right = 5 }
            for vert in 0..<size {
                for j in 0..<2 {
                    let x = right - j
                    let upward = ((right + 1) & 2) == 0
                    let y = upward ? size - 1 - vert : vert
                    if !isFunction[y * size + x], i < data.count * 8 {
                        modules[y * size + x] = (Int(data[i >> 3]) >> (7 - (i & 7))) & 1 == 1
                        i += 1
                    }
                }
            }
            right -= 2
        }
    }

    mutating func applyMask(_ mask: Int) {
        for y in 0..<size {
            for x in 0..<size where !isFunction[y * size + x] {
                let invert: Bool
                switch mask {
                case 0: invert = (x + y) % 2 == 0
                case 1: invert = y % 2 == 0
                case 2: invert = x % 3 == 0
                case 3: invert = (x + y) % 3 == 0
                case 4: invert = (x / 3 + y / 2) % 2 == 0
                case 5: invert = x * y % 2 + x * y % 3 == 0
                case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0
                default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if invert { modules[y * size + x].toggle() }
            }
        }
    }

    // MARK: Penalty (ISO 18004 §7.8.3, as interpreted by Nayuki)

    func penaltyScore() -> Int {
        var result = 0
        for y in 0..<size { result += linePenalty { get($0, y) } }
        for x in 0..<size { result += linePenalty { get(x, $0) } }
        for y in 0..<(size - 1) {
            for x in 0..<(size - 1) {
                let c = get(x, y)
                if c == get(x + 1, y), c == get(x, y + 1), c == get(x + 1, y + 1) { result += 3 }
            }
        }
        let dark = modules.reduce(0) { $0 + ($1 ? 1 : 0) }
        let total = size * size
        let k = (abs(dark * 20 - total * 10) + total - 1) / total - 1
        result += k * 10
        return result
    }

    private func linePenalty(_ at: (Int) -> Bool) -> Int {
        var result = 0
        var runColor = false
        var runLength = 0
        var history = [Int](repeating: 0, count: 7)
        for i in 0..<size {
            if at(i) == runColor {
                runLength += 1
                if runLength == 5 { result += 3 } else if runLength > 5 { result += 1 }
            } else {
                addHistory(runLength, &history)
                if !runColor { result += countFinderLike(history) * 40 }
                runColor = at(i)
                runLength = 1
            }
        }
        // Terminate: count the trailing run plus the light border.
        if runColor {
            addHistory(runLength, &history)
            runLength = 0
        }
        runLength += size
        addHistory(runLength, &history)
        result += countFinderLike(history) * 40
        return result
    }

    private func addHistory(_ length: Int, _ history: inout [Int]) {
        var length = length
        if history[0] == 0 { length += size } // light border before the first run
        history.removeLast()
        history.insert(length, at: 0)
    }

    private func countFinderLike(_ h: [Int]) -> Int {
        let n = h[1]
        let core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n
        return (core && h[0] >= n * 4 && h[6] >= n ? 1 : 0) + (core && h[6] >= n * 4 && h[0] >= n ? 1 : 0)
    }
}
