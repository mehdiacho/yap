import SwiftUI

struct LibraryView: View {
    @EnvironmentObject private var auth: AuthService
    @StateObject private var repo = SessionRepository()
    @StateObject private var recorder = Recorder()
    @State private var showPlans = false
    @State private var banner: String?

    var body: some View {
        NavigationStack {
            ZStack {
                Palette.bg.ignoresSafeArea()
                content
                VStack {
                    Spacer()
                    CaptureBar(recorder: recorder, repo: repo, banner: $banner)
                        .padding(.horizontal, 20)
                        .padding(.bottom, 12)
                }
            }
            .navigationTitle("Notes")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("Plans") { showPlans = true }
                        Button("Sign out", role: .destructive) {
                            repo.stop()
                            auth.signOut()
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .sheet(isPresented: $showPlans) { PlansPlaceholderView() }
        }
        .tint(Palette.accent)
        .task { repo.start() }
        .overlay(alignment: .top) {
            if let banner {
                Text(banner)
                    .font(.footnote)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(Palette.panel, in: Capsule())
                    .foregroundStyle(.white)
                    .padding(.top, 6)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .task {
                        try? await Task.sleep(for: .seconds(4))
                        withAnimation { self.banner = nil }
                    }
            }
        }
        .animation(.easeInOut, value: banner)
    }

    @ViewBuilder
    private var content: some View {
        if repo.loading {
            ProgressView().tint(.white)
        } else if let error = repo.error {
            VStack(spacing: 8) {
                Text("Couldn't load your notes.").foregroundStyle(.white)
                Text(error).font(.footnote).foregroundStyle(Palette.subdued)
            }
            .padding(30)
            .multilineTextAlignment(.center)
        } else if repo.sessions.isEmpty {
            VStack(spacing: 6) {
                Text("No notes yet.").foregroundStyle(.white)
                Text("Hold the button to record one.")
                    .font(.footnote).foregroundStyle(Palette.subdued)
            }
        } else {
            List {
                ForEach(repo.sessions) { session in
                    NavigationLink(value: session.id) {
                        SessionRow(session: session)
                    }
                    .listRowBackground(Palette.panel)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .safeAreaPadding(.bottom, 90)
            .navigationDestination(for: String.self) { id in
                if let s = repo.sessions.first(where: { $0.id == id }) {
                    NoteDetailView(session: s)
                }
            }
        }
    }
}

private struct SessionRow: View {
    let session: Session

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(session.title)
                    .font(.headline)
                    .foregroundStyle(.white)
                    .lineLimit(1)
                Spacer()
                if session.isTranscribing {
                    ProgressView().scaleEffect(0.7).tint(Palette.subdued)
                }
            }
            Text(session.body.isEmpty ? "No transcript yet." : session.body)
                .font(.footnote)
                .foregroundStyle(Palette.subdued)
                .lineLimit(2)
            HStack(spacing: 8) {
                Text(Format.day(session.createdAt))
                Text("·")
                Text(Format.duration(session.durationMs))
                if session.status == "QUOTA" {
                    Text("· limit reached").foregroundStyle(.orange)
                }
            }
            .font(.caption2)
            .foregroundStyle(Palette.subdued)
        }
        .padding(.vertical, 4)
    }
}

/// Record, upload, and let the note fill itself in.
private struct CaptureBar: View {
    @ObservedObject var recorder: Recorder
    @ObservedObject var repo: SessionRepository
    @Binding var banner: String?

    var body: some View {
        HStack(spacing: 14) {
            if recorder.isRecording {
                Circle()
                    .fill(Color.red)
                    .frame(width: 10, height: 10)
                    .opacity(Double(0.4 + recorder.level * 0.6))
                Text(Format.duration(Int64(recorder.elapsed * 1000)))
                    .monospacedDigit()
                    .foregroundStyle(.white)
            }
            Spacer()
            Button {
                Task { await toggle() }
            } label: {
                Image(systemName: recorder.isRecording ? "stop.fill" : "mic.fill")
                    .font(.title2)
                    .frame(width: 62, height: 62)
                    .background(recorder.isRecording ? Color.red : Palette.accent, in: Circle())
                    .foregroundStyle(.black)
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial, in: Capsule())
    }

    private func toggle() async {
        if recorder.isRecording {
            guard let done = recorder.stop() else {
                banner = "That was too short to keep."
                return
            }
            await send(done.url, done.durationMs)
        } else {
            await recorder.start()
            if let e = recorder.error { banner = e }
        }
    }

    /// Write the note FIRST, then upload.
    ///
    /// The note appears in the list immediately as TRANSCRIBING, which is both better to
    /// look at and the thing that makes a slow or dropped upload survivable: the backend
    /// writes its result into a document that already exists, and the snapshot listener
    /// fills it in whenever that happens — even if this app was closed in between.
    private func send(_ url: URL, _ durationMs: Int64) async {
        let id = UUID().uuidString
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let pending = Session(
            id: id, title: "Recording", draftText: "", formattedText: nil,
            storagePath: nil, videoStoragePath: nil, wordTimings: [],
            status: "TRANSCRIBING", statusMessage: nil, createdAt: now,
            durationMs: durationMs, category: "Memo", topicCluster: "General",
            trashedAt: nil, pinned: false, actionItems: [], extraRecordings: []
        )
        do {
            try await repo.upsert(pending)
            let path = try await TranscriptionClient.transcribe(
                fileURL: url, durationMs: durationMs, sessionId: id
            )
            try? await repo.upsert(pending, extra: ["storagePath": path])
        } catch {
            banner = TranscriptionClient.readable(error)
            try? await repo.upsert(pending, extra: [
                "status": "FAILED",
                "statusMessage": TranscriptionClient.readable(error),
            ])
        }
        try? FileManager.default.removeItem(at: url)
    }
}
