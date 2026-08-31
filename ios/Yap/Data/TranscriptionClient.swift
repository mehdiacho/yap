import FirebaseAuth
import FirebaseFunctions
import FirebaseStorage
import Foundation

/// Upload a recording, then ask the backend to transcribe it.
///
/// Same two steps and the same `transcribeSession` callable the Android client uses, in the
/// same region. Nothing about the server needed changing for iOS — it takes a Storage path
/// and a MIME type and does not care what recorded the bytes.
enum TranscriptionClient {
    private static let functions = Functions.functions(region: "africa-south1")

    /// Firestore is the authoritative result channel (docs/API_CONTRACT.md).
    ///
    /// The callable's own response is only a convenience: a long recording easily outlives
    /// the client's timeout, and when that happens the work still lands — the backend writes
    /// the finished note into `users/{uid}/sessions/{id}` and the snapshot listener picks it
    /// up. So this returns as soon as the request is accepted and lets the note update
    /// itself, rather than holding a spinner against a call that may never return.
    static func transcribe(
        fileURL: URL,
        durationMs: Int64,
        sessionId: String
    ) async throws -> String {
        guard let uid = Auth.auth().currentUser?.uid else {
            throw NSError(
                domain: "Yap", code: 401,
                userInfo: [NSLocalizedDescriptionKey: "Sign in first."]
            )
        }

        // Deterministic path, matching Android's `users/{uid}/audio/{filename}` — a note that
        // loses its storagePath can then still be found rather than re-uploaded.
        let path = "users/\(uid)/audio/\(fileURL.lastPathComponent)"
        let ref = Storage.storage().reference(withPath: path)

        _ = try await withCheckedThrowingContinuation { (cont: CheckedContinuation<StorageMetadata, Error>) in
            let meta = StorageMetadata()
            meta.contentType = "audio/wav"
            ref.putFile(from: fileURL, metadata: meta) { md, err in
                if let err { cont.resume(throwing: err) } else if let md {
                    cont.resume(returning: md)
                } else {
                    cont.resume(throwing: NSError(domain: "Yap", code: -1))
                }
            }
        }

        _ = try await functions.httpsCallable("transcribeSession").call([
            "storagePath": path,
            "mimeType": "audio/wav",
            "durationMs": durationMs,
            "sessionId": sessionId,
        ])
        return path
    }

    /// Turns a callable failure into something worth showing someone.
    ///
    /// `resource-exhausted` is the monthly cap and is the one case that is not a fault at
    /// all — the backend already marks the note QUOTA and keeps the audio, so the note is
    /// transcribable later rather than lost.
    static func readable(_ error: Error) -> String {
        let ns = error as NSError
        guard ns.domain == FunctionsErrorDomain,
              let code = FunctionsErrorCode(rawValue: ns.code) else {
            return ns.localizedDescription
        }
        switch code {
        case .resourceExhausted: return "You've hit this month's transcription limit."
        case .unauthenticated: return "Sign in again to transcribe."
        case .deadlineExceeded:
            return "Still working — the note will fill in on its own."
        default: return ns.localizedDescription
        }
    }
}
