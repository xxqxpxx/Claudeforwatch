import AVFoundation

/// Read-aloud for replies (on-device `AVSpeechSynthesizer`; no network).
@MainActor
final class Speech {
    static let shared = Speech()

    private let synthesizer = AVSpeechSynthesizer()

    var isSpeaking: Bool { synthesizer.isSpeaking }

    func speak(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        stop()
        let utterance = AVSpeechUtterance(string: trimmed)
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate
        if let language = Locale.preferredLanguages.first {
            utterance.voice = AVSpeechSynthesisVoice(language: language)
        }
        synthesizer.speak(utterance)
    }

    func stop() {
        if synthesizer.isSpeaking { synthesizer.stopSpeaking(at: .immediate) }
    }
}
