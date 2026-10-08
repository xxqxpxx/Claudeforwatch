import ClaudeWatchKit
import SwiftUI
import WidgetKit

/// Smart Stack / complication: sessions needing you + usage. Reads the JSON
/// snapshot the app writes to the App Group; never touches the network or
/// the Keychain.
struct StatusEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot
}

struct StatusProvider: TimelineProvider {
    static let refreshInterval: TimeInterval = 15 * 60

    func placeholder(in context: Context) -> StatusEntry {
        StatusEntry(date: Date(), snapshot: .placeholder)
    }

    func getSnapshot(in context: Context, completion: @escaping (StatusEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : current())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<StatusEntry>) -> Void) {
        let entry = current()
        completion(Timeline(entries: [entry], policy: .after(entry.date.addingTimeInterval(Self.refreshInterval))))
    }

    private func current() -> StatusEntry {
        let snapshot = SharedContainer.directory.flatMap {
            SharedJSONFile.read(WidgetSnapshot.self, named: WidgetSnapshot.fileName, in: $0)
        } ?? WidgetSnapshot(updatedAt: 0)
        return StatusEntry(date: Date(), snapshot: snapshot)
    }
}

struct StatusWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: StatusEntry

    private var count: Int? { entry.snapshot.needsActionCount }
    private var usage: Int? { entry.snapshot.usagePercent }

    var body: some View {
        switch family {
        case .accessoryCorner:
            corner
        default:
            rectangular
        }
    }

    private var rectangular: some View {
        VStack(alignment: .leading, spacing: 2) {
            Label("Claude", systemImage: "bubble.left.and.bubble.right.fill")
                .font(.headline)
                .widgetAccentable()
            if !entry.snapshot.signedIn {
                Text("Open to sign in").font(.footnote)
            } else if let count {
                Text(count == 0 ? "No sessions waiting" : "\(count) need\(count == 1 ? "s" : "") you")
                    .font(.footnote)
            } else {
                Text("Tap to ask").font(.footnote)
            }
            if let usage {
                Gauge(value: Double(usage), in: 0...100) {
                    Text("Usage")
                } currentValueLabel: {
                    Text("\(usage)%")
                }
                .gaugeStyle(.accessoryLinearCapacity)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var corner: some View {
        ZStack {
            AccessoryWidgetBackground()
            if let count, count > 0 {
                Text("\(count)").font(.title3.bold()).widgetAccentable()
            } else {
                Image(systemName: "bubble.left.fill").font(.title3).widgetAccentable()
            }
        }
        .widgetLabel {
            if let usage {
                Gauge(value: Double(usage), in: 0...100) {
                    Text("Usage")
                } currentValueLabel: {
                    Text("\(usage)%")
                }
            } else if let count {
                Text(count == 0 ? "All clear" : "\(count) waiting")
            } else {
                Text("Ask Claude")
            }
        }
    }
}

struct StatusWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: SharedContainer.widgetKind, provider: StatusProvider()) { entry in
            StatusWidgetView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
                .widgetURL(entry.snapshot.needsActionCount != nil ? SharedContainer.sessionsURL : SharedContainer.askURL)
        }
        .configurationDisplayName("Claude")
        .description("Sessions waiting for you and your usage.")
        .supportedFamilies([.accessoryRectangular, .accessoryCorner])
    }
}

@main
struct ClaudeWidgetBundle: WidgetBundle {
    var body: some Widget {
        StatusWidget()
    }
}
