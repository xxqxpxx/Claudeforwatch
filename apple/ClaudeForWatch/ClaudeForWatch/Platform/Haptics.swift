import WatchKit

@MainActor
enum Haptics {
    /// A permission prompt arrived (PROTOCOL §5.5).
    static func attention() { WKInterfaceDevice.current().play(.notification) }
    static func success() { WKInterfaceDevice.current().play(.success) }
    static func failure() { WKInterfaceDevice.current().play(.failure) }
    static func tap() { WKInterfaceDevice.current().play(.click) }
    static func start() { WKInterfaceDevice.current().play(.start) }
}
