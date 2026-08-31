import FirebaseAuth
import FirebaseCore
import GoogleSignIn
import SwiftUI

/// Sign-in, against the same Firebase project the Android app uses.
///
/// That is the whole point of the exercise: an account made on Android signs in here and its
/// notes are already present, because both clients read `users/{uid}/sessions` from one
/// Firestore. Nothing is migrated or synced — there is only ever one copy.
@MainActor
final class AuthService: ObservableObject {
    @Published private(set) var user: User?
    @Published var busy = false
    @Published var error: String?

    private var handle: AuthStateDidChangeListenerHandle?

    init() {
        user = Auth.auth().currentUser
        handle = Auth.auth().addStateDidChangeListener { [weak self] _, u in
            Task { @MainActor in self?.user = u }
        }
    }

    deinit {
        if let handle { Auth.auth().removeStateDidChangeListener(handle) }
    }

    var isSignedIn: Bool { user != nil }

    func signIn(email: String, password: String) async {
        await run { try await Auth.auth().signIn(withEmail: email, password: password) }
    }

    func signUp(email: String, password: String) async {
        await run { try await Auth.auth().createUser(withEmail: email, password: password) }
    }

    /// Password reset. Deliberately fire-once with no retry: the Android app shipped a bug
    /// where an un-debounced button sent a burst of identical reset mails (fixed in 42b9355),
    /// and the same mistake is just as available here.
    func sendReset(email: String) async {
        guard !busy else { return }
        busy = true
        error = nil
        do {
            try await Auth.auth().sendPasswordReset(withEmail: email)
            error = "Reset link sent to \(email)."
        } catch {
            self.error = Self.readable(error)
        }
        busy = false
    }

    /// Google sign-in via the system browser.
    ///
    /// `GIDSignIn` opens an `ASWebAuthenticationSession` and returns through the app's
    /// registered URL scheme (the REVERSED_CLIENT_ID in project.yml). No paid Apple
    /// developer account is involved — this is ordinary OAuth, so it works on a sideloaded
    /// build exactly as it would on an App Store one.
    func signInWithGoogle() async {
        guard let clientID = FirebaseApp.app()?.options.clientID else {
            error = "Google sign-in isn't configured in this build."
            return
        }
        guard let root = Self.topViewController() else {
            error = "Couldn't open the sign-in window."
            return
        }
        busy = true
        error = nil
        do {
            GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
            let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: root)
            guard let idToken = result.user.idToken?.tokenString else {
                throw NSError(
                    domain: "Yap", code: -1,
                    userInfo: [NSLocalizedDescriptionKey: "Google returned no identity token."]
                )
            }
            let credential = GoogleAuthProvider.credential(
                withIDToken: idToken,
                accessToken: result.user.accessToken.tokenString
            )
            _ = try await Auth.auth().signIn(with: credential)
        } catch {
            // A cancelled sign-in is a decision, not a failure — saying "error" would be wrong.
            if (error as NSError).code != GIDSignInError.canceled.rawValue {
                self.error = Self.readable(error)
            }
        }
        busy = false
    }

    func signOut() {
        try? Auth.auth().signOut()
        GIDSignIn.sharedInstance.signOut()
    }

    private func run(_ op: @escaping () async throws -> Any) async {
        guard !busy else { return }
        busy = true
        error = nil
        do { _ = try await op() } catch { self.error = Self.readable(error) }
        busy = false
    }

    /// Firebase's raw messages name internal codes at people who just mistyped a password.
    private static func readable(_ error: Error) -> String {
        let ns = error as NSError
        switch AuthErrorCode(rawValue: ns.code) {
        case .wrongPassword, .invalidCredential: return "That email and password don't match."
        case .invalidEmail: return "That doesn't look like an email address."
        case .emailAlreadyInUse: return "There's already an account with that email."
        case .weakPassword: return "Pick a longer password — at least six characters."
        case .networkError: return "No connection. Try again when you're back online."
        case .userNotFound: return "No account with that email."
        case .tooManyRequests: return "Too many attempts. Wait a minute and try again."
        default: return ns.localizedDescription
        }
    }

    /// GoogleSignIn needs a presenting controller, and SwiftUI has no direct handle on one.
    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        var top = scene?.windows.first(where: \.isKeyWindow)?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
