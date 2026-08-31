import Foundation

/// A note, mirroring the Android `Session` exactly.
///
/// The field names below are load-bearing: this reads the SAME Firestore documents the
/// Android app writes (`users/{uid}/sessions/{id}`), so a name that drifts doesn't fail
/// loudly — it silently reads as nil and the note shows up blank. They are matched against
/// `FirestoreSessionRepository.toMap()` in the Android source, not guessed.
///
/// Decoding is hand-written rather than Codable for the same reason the Android side is
/// hand-mapped: these documents were written by another platform over many app versions,
/// so every field has to tolerate being absent, and a Codable failure throws away the whole
/// note over one unexpected key.
struct WordTiming: Hashable {
    let text: String
    let startMs: Int64
    let endMs: Int64
}

struct ActionItem: Identifiable, Hashable {
    let id: String
    let text: String
    var done: Bool
    let doneAt: Int64?
}

struct ExtraRecording: Hashable {
    let storagePath: String?
    let durationMs: Int64
}

struct Session: Identifiable, Hashable {
    let id: String
    let title: String
    let draftText: String
    let formattedText: String?
    /// Where the audio lives in Cloud Storage. The ONLY playable source on iOS — see below.
    let storagePath: String?
    let videoStoragePath: String?
    let wordTimings: [WordTiming]
    let status: String
    let statusMessage: String?
    let createdAt: Int64
    let durationMs: Int64
    let category: String
    let topicCluster: String
    let trashedAt: Int64?
    let pinned: Bool
    let actionItems: [ActionItem]
    let extraRecordings: [ExtraRecording]

    /// What the note shows: the polished text when there is one, the raw transcript otherwise.
    /// Same precedence as Android, so the same note doesn't read differently on two phones.
    var body: String {
        if let f = formattedText, !f.isEmpty { return f }
        return draftText
    }

    var isTranscribing: Bool { status == "TRANSCRIBING" }
    var hasAudio: Bool { storagePath != nil }

    /// Deliberately NOT modelled: `audioPath` and `videoPath`.
    ///
    /// Those are absolute paths into an Android device's private storage
    /// (`/data/user/0/app.greenforrest.yap/files/recordings/…`). Every note written by the
    /// Android app carries one, and on iOS it is meaningless — reading it would produce a
    /// play button that can never work. Cloud Storage is the only shared source of bytes,
    /// so this app plays `storagePath` or nothing at all.
    static func from(id: String, data: [String: Any]) -> Session {
        func str(_ k: String) -> String? {
            guard let v = data[k] as? String, !v.isEmpty else { return nil }
            return v
        }
        func int(_ k: String) -> Int64? {
            if let n = data[k] as? NSNumber { return n.int64Value }
            return nil
        }
        let maps: ([String: Any], String) -> [[String: Any]] = { d, k in
            (d[k] as? [[String: Any]]) ?? []
        }

        return Session(
            id: id,
            title: str("title") ?? "Untitled",
            draftText: str("draftText") ?? "",
            formattedText: str("formattedText"),
            storagePath: str("storagePath"),
            videoStoragePath: str("videoStoragePath"),
            wordTimings: maps(data, "wordTimings").compactMap { w in
                guard let t = w["text"] as? String else { return nil }
                return WordTiming(
                    text: t,
                    startMs: (w["startMs"] as? NSNumber)?.int64Value ?? 0,
                    endMs: (w["endMs"] as? NSNumber)?.int64Value ?? 0
                )
            },
            status: str("status") ?? "DONE",
            statusMessage: str("statusMessage"),
            createdAt: int("createdAt") ?? 0,
            durationMs: int("durationMs") ?? 0,
            category: str("category") ?? "Memo",
            topicCluster: str("topicCluster") ?? "General",
            trashedAt: int("trashedAt"),
            pinned: (data["pinned"] as? Bool) ?? false,
            actionItems: maps(data, "actionItems").compactMap { a in
                guard let text = a["text"] as? String else { return nil }
                return ActionItem(
                    id: (a["id"] as? String) ?? UUID().uuidString,
                    text: text,
                    done: (a["done"] as? Bool) ?? false,
                    doneAt: (a["doneAt"] as? NSNumber)?.int64Value
                )
            },
            extraRecordings: maps(data, "extraRecordings").map { e in
                ExtraRecording(
                    storagePath: e["storagePath"] as? String,
                    durationMs: (e["durationMs"] as? NSNumber)?.int64Value ?? 0
                )
            }
        )
    }
}

enum Format {
    /// Matches Android's `UsageInsights.durationLabel` so the same note reads the same length.
    static func duration(_ ms: Int64) -> String {
        let total = ms / 1000
        if total < 60 { return "\(total)s" }
        if total < 3600 { return "\(total / 60)m" }
        let h = total / 3600
        let m = (total % 3600) / 60
        return m == 0 ? "\(h)h" : "\(h)h \(m)m"
    }

    static func day(_ ms: Int64) -> String {
        let f = DateFormatter()
        f.dateFormat = "d MMM"
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(ms) / 1000))
    }
}
