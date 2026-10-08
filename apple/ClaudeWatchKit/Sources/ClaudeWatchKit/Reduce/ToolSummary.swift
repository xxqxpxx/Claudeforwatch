import Foundation

/// One-line summary of a tool call's input (docs/PROTOCOL.md §5.3):
/// Bash → `command`; Edit/Write/Read → `file_path`; otherwise the first string
/// value of the input object in document order. Only the first non-empty line
/// is kept; truncation is left to the UI.
public enum ToolSummary {
    public static func summarize(tool: String, input: JSONValue) -> String {
        let raw: String?
        switch tool {
        case "Bash":
            raw = input["command"]?.stringValue
        case "Edit", "Write", "Read":
            raw = input["file_path"]?.stringValue
        case "AskUserQuestion":
            // Not in §5.3: the first question reads better than nothing (the
            // input has no top-level string). Falls back to the generic rule.
            raw = input["questions"]?.arrayValue?.first?["question"]?.stringValue
                ?? firstString(in: input)
        default:
            raw = firstString(in: input)
        }
        return firstLine(raw ?? "")
    }

    static func firstString(in input: JSONValue) -> String? {
        guard let obj = input.objectValue else { return input.stringValue }
        for (_, value) in obj {
            if let s = value.stringValue { return s }
        }
        return nil
    }

    static func firstLine(_ s: String) -> String {
        for line in s.split(omittingEmptySubsequences: true, whereSeparator: { $0 == "\n" || $0 == "\r" }) {
            let t = line.trimmingCharacters(in: .whitespaces)
            if !t.isEmpty { return t }
        }
        return ""
    }
}
