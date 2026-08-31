import FirebaseAuth
import FirebaseFirestore
import FirebaseStorage
import Foundation

/// Live view of `users/{uid}/sessions` — the same collection the Android app writes.
///
/// A snapshot listener rather than a fetch, for the reason it matters most here: the note
/// this app just recorded is written by the backend minutes later, and a transcript that
/// only appears if you remember to pull-to-refresh reads as a bug. It also means a note
/// finished on the Android phone lands on the iPhone while it is open.
@MainActor
final class SessionRepository: ObservableObject {
    @Published private(set) var sessions: [Session] = []
    @Published private(set) var loading = true
    @Published var error: String?

    private var listener: ListenerRegistration?

    func start() {
        guard listener == nil, let uid = Auth.auth().currentUser?.uid else { return }
        loading = true
        listener = Firestore.firestore()
            .collection("users").document(uid).collection("sessions")
            .order(by: "createdAt", descending: true)
            .addSnapshotListener { [weak self] snap, err in
                Task { @MainActor in
                    guard let self else { return }
                    self.loading = false
                    if let err {
                        self.error = err.localizedDescription
                        return
                    }
                    self.error = nil
                    self.sessions = (snap?.documents ?? [])
                        .map { Session.from(id: $0.documentID, data: $0.data()) }
                        // Trashed notes still exist for 30 days on Android. Showing them
                        // would mean deleting a note on one phone changes nothing on the other.
                        .filter { $0.trashedAt == nil }
                }
            }
    }

    func stop() {
        listener?.remove()
        listener = nil
        sessions = []
        loading = true
    }

    func upsert(_ session: Session, extra: [String: Any] = [:]) async throws {
        guard let uid = Auth.auth().currentUser?.uid else { return }
        var data: [String: Any] = [
            "title": session.title,
            "draftText": session.draftText,
            "status": session.status,
            "createdAt": session.createdAt,
            "durationMs": session.durationMs,
            "category": session.category,
            "topicCluster": session.topicCluster,
            "pinned": session.pinned,
        ]
        data.merge(extra) { _, new in new }
        try await Firestore.firestore()
            .collection("users").document(uid).collection("sessions")
            .document(session.id)
            // Merge, never overwrite. The backend writes the transcript into this same
            // document while we may still be holding a stale copy of it; a full set() would
            // erase whatever it had just finished putting there.
            .setData(data, merge: true)
    }

    func trash(_ id: String) async throws {
        guard let uid = Auth.auth().currentUser?.uid else { return }
        try await Firestore.firestore()
            .collection("users").document(uid).collection("sessions").document(id)
            .setData(["trashedAt": Int64(Date().timeIntervalSince1970 * 1000)], merge: true)
    }
}

/// Cloud Storage access for a note's audio.
enum AudioStore {
    /// A temporary local URL for a note's recording, downloaded on demand.
    ///
    /// Android notes reference bytes this device has never held, so playback always goes
    /// through Storage. Cached in the temp directory: re-downloading a recording every time
    /// the user taps play would be slow and would bill them for the same bytes repeatedly.
    static func localURL(for storagePath: String) async throws -> URL {
        let name = storagePath.replacingOccurrences(of: "/", with: "_")
        let cached = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        if FileManager.default.fileExists(atPath: cached.path) { return cached }

        let ref = Storage.storage().reference(withPath: storagePath)
        return try await withCheckedThrowingContinuation { cont in
            ref.write(toFile: cached) { url, err in
                if let err { cont.resume(throwing: err) } else { cont.resume(returning: url ?? cached) }
            }
        }
    }
}
