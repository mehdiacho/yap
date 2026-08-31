import AVFoundation
import Foundation

/// Microphone capture, written straight to a 16 kHz mono 16-bit WAV.
///
/// The format is not a preference — it is the one the backend measures exactly. `index.ts`
/// derives billable duration from byte count for `audio/wav` (`audio.length / 32000`) and
/// falls back to a conservative size floor for anything compressed, because a client can
/// otherwise under-report how long a recording was. Matching Android's format byte for byte
/// means an iOS note is metered by the same arithmetic as an Android one instead of through
/// the floor, which for compressed audio over-charges short clips.
///
/// There is deliberately NO live draft here. On Android that is a fed-stream
/// `SpeechRecognizer` (`LiveDraftEngine`) whose whole design exists to avoid mic contention;
/// iOS `SFSpeechRecognizer` has different limits, its own permission, and on-device model
/// downloads. Recording goes straight to the cloud and the transcript comes back — one path,
/// nothing to reconcile.
@MainActor
final class Recorder: NSObject, ObservableObject {
    @Published private(set) var isRecording = false
    @Published private(set) var elapsed: TimeInterval = 0
    /// 0…1, for the level meter. Smoothed the same way the Android meter is.
    @Published private(set) var level: Float = 0
    @Published var error: String?

    private var recorder: AVAudioRecorder?
    private var timer: Timer?
    private var fileURL: URL?

    private static let settings: [String: Any] = [
        AVFormatIDKey: Int(kAudioFormatLinearPCM),
        AVSampleRateKey: 16_000.0,
        AVNumberOfChannelsKey: 1,
        AVLinearPCMBitDepthKey: 16,
        AVLinearPCMIsFloatKey: false,
        AVLinearPCMIsBigEndianKey: false,
    ]

    func requestPermission() async -> Bool {
        await withCheckedContinuation { cont in
            AVAudioApplication.requestRecordPermission { granted in
                cont.resume(returning: granted)
            }
        }
    }

    func start() async {
        guard !isRecording else { return }
        guard await requestPermission() else {
            error = "Yap needs microphone access to record."
            return
        }
        do {
            let session = AVAudioSession.sharedInstance()
            // .record rather than .playAndRecord: nothing is played while capturing, and
            // asking for playback too would take audio focus from whatever else is going.
            try session.setCategory(.record, mode: .default)
            try session.setActive(true)

            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("rec_\(Int(Date().timeIntervalSince1970 * 1000)).wav")
            let r = try AVAudioRecorder(url: url, settings: Self.settings)
            r.isMeteringEnabled = true
            guard r.record() else {
                error = "The microphone wouldn't start."
                return
            }
            recorder = r
            fileURL = url
            isRecording = true
            elapsed = 0
            timer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.tick() }
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func tick() {
        guard let r = recorder, r.isRecording else { return }
        r.updateMeters()
        elapsed = r.currentTime
        // averagePower is dBFS, roughly -160…0. Map through the same 50 dB floor the Android
        // meter uses so the bar behaves identically at arm's length.
        let db = r.averagePower(forChannel: 0)
        let mapped = max(0, min(1, (db + 50) / 50))
        level = level * 0.35 + mapped * 0.65
    }

    /// Stops and hands back the finished file, or nil if nothing usable was captured.
    func stop() -> (url: URL, durationMs: Int64)? {
        guard let r = recorder, let url = fileURL else { return nil }
        let seconds = r.currentTime
        r.stop()
        timer?.invalidate()
        timer = nil
        recorder = nil
        fileURL = nil
        isRecording = false
        level = 0
        try? AVAudioSession.sharedInstance().setActive(false)

        // A tap that starts and stops inside a moment leaves a header and no audio; sending
        // it would spend a transcription on silence and produce an empty note.
        guard seconds >= 0.5,
              let size = try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64,
              size > 1024
        else {
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        return (url, Int64(seconds * 1000))
    }

    func discard() {
        _ = stop()
    }
}
