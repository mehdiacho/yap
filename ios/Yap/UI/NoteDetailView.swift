import AVFoundation
import SwiftUI

struct NoteDetailView: View {
    let session: Session
    @State private var player: AVAudioPlayer?
    @State private var loadingAudio = false
    @State private var audioError: String?

    var body: some View {
        ZStack {
            Palette.bg.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(session.title)
                        .font(.title2.bold())
                        .foregroundStyle(.white)

                    HStack(spacing: 8) {
                        Label(Format.duration(session.durationMs), systemImage: "waveform")
                        Text("·")
                        Text(session.category)
                    }
                    .font(.caption)
                    .foregroundStyle(Palette.subdued)

                    if session.hasAudio {
                        audioControl
                    } else {
                        // An Android note whose audio never reached Cloud Storage references
                        // a path on that device's private filesystem. There is nothing here
                        // to play, and a dead play button is worse than none.
                        Text("Audio isn't in the cloud for this note.")
                            .font(.footnote)
                            .foregroundStyle(Palette.subdued)
                    }

                    Divider().overlay(Color.white.opacity(0.1))

                    if session.isTranscribing {
                        HStack(spacing: 8) {
                            ProgressView().tint(Palette.subdued)
                            Text("Transcribing…").foregroundStyle(Palette.subdued)
                        }
                    } else if session.body.isEmpty {
                        Text(session.statusMessage ?? "No transcript.")
                            .foregroundStyle(Palette.subdued)
                    } else {
                        // Rendered as plain text on purpose: the backend returns Markdown and
                        // Android has a native renderer for it. Reproducing that properly is
                        // its own piece of work, and half-rendered Markdown reads worse than
                        // none — see ios/README.md for what is and isn't ported.
                        Text(session.body)
                            .font(.body)
                            .foregroundStyle(.white.opacity(0.92))
                            .textSelection(.enabled)
                    }

                    if !session.actionItems.isEmpty {
                        Text("TASKS")
                            .font(.caption2.bold())
                            .foregroundStyle(Palette.subdued)
                        ForEach(session.actionItems) { item in
                            HStack(alignment: .top, spacing: 8) {
                                Image(systemName: item.done ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(item.done ? Palette.accent : Palette.subdued)
                                Text(item.text)
                                    .strikethrough(item.done)
                                    .foregroundStyle(.white.opacity(item.done ? 0.5 : 0.9))
                            }
                            .font(.callout)
                        }
                    }
                }
                .padding(20)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
    }

    private var audioControl: some View {
        HStack(spacing: 12) {
            Button {
                Task { await togglePlay() }
            } label: {
                Image(systemName: player?.isPlaying == true ? "pause.fill" : "play.fill")
                    .frame(width: 44, height: 44)
                    .background(Palette.panel, in: Circle())
                    .foregroundStyle(.white)
            }
            .disabled(loadingAudio)

            if loadingAudio {
                ProgressView().tint(Palette.subdued)
                Text("Fetching audio…")
                    .font(.footnote).foregroundStyle(Palette.subdued)
            } else if let audioError {
                Text(audioError).font(.footnote).foregroundStyle(.orange)
            }
            Spacer()
        }
    }

    private func togglePlay() async {
        if let p = player {
            p.isPlaying ? p.pause() : p.play()
            // Nudge SwiftUI: AVAudioPlayer isn't observable, so the icon won't flip on its own.
            player = p
            return
        }
        guard let path = session.storagePath else { return }
        loadingAudio = true
        audioError = nil
        do {
            let url = try await AudioStore.localURL(for: path)
            try AVAudioSession.sharedInstance().setCategory(.playback)
            try AVAudioSession.sharedInstance().setActive(true)
            let p = try AVAudioPlayer(contentsOf: url)
            p.play()
            player = p
        } catch {
            audioError = "Couldn't play this recording."
        }
        loadingAudio = false
    }
}
