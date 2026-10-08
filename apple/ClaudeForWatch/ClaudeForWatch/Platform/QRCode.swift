import ClaudeWatchKit
import SwiftUI

/// Draws a `ClaudeWatchKit.QRCode` (Core Image is not available on watchOS,
/// so the matrix comes from the pure-Swift encoder). Modules are snapped to
/// whole device pixels and surrounded by the 4-module quiet zone so phone
/// cameras can read a version-13 code off a 41–49 mm screen.
struct QRCodeView: View {
    let code: QRCode
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        Canvas { context, size in
            let quiet = 4
            let total = code.size + quiet * 2
            let sidePixels = min(size.width, size.height) * displayScale
            let modulePixels = max(1, floor(sidePixels / CGFloat(total)))
            let module = modulePixels / displayScale
            let side = module * CGFloat(total)
            let origin = CGPoint(x: (size.width - side) / 2, y: (size.height - side) / 2)

            context.fill(Path(CGRect(origin: origin, size: CGSize(width: side, height: side))), with: .color(.white))
            var dark = Path()
            for y in 0..<code.size {
                for x in 0..<code.size where code[x: x, y: y] {
                    dark.addRect(CGRect(
                        x: origin.x + CGFloat(x + quiet) * module,
                        y: origin.y + CGFloat(y + quiet) * module,
                        width: module, height: module
                    ))
                }
            }
            context.fill(dark, with: .color(.black))
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityLabel("Sign-in QR code")
    }
}
