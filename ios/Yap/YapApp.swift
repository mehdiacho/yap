import FirebaseCore
import GoogleSignIn
import SwiftUI

@main
struct YapApp: App {
    @StateObject private var auth: AuthService

    init() {
        FirebaseApp.configure()
        _auth = StateObject(wrappedValue: AuthService())
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(auth)
                .preferredColorScheme(.dark)
                // Google Sign-In hands control to Safari and comes back through the app's
                // URL scheme; without this the browser opens and never returns.
                .onOpenURL { GIDSignIn.sharedInstance.handle($0) }
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var auth: AuthService

    var body: some View {
        Group {
            if auth.isSignedIn {
                LibraryView()
            } else {
                AuthView()
            }
        }
        .animation(.easeInOut(duration: 0.25), value: auth.isSignedIn)
    }
}

/// The small amount of shared styling this build needs.
///
/// Deliberately not a port of the Android theme. Yap's identity there is a set of Compose
/// tokens and an animated brand gradient reserved for identity moments; half-translating it
/// into SwiftUI would produce something that reads as a worse copy of the real app. This is
/// plain and dark, and looks like a deliberate test build rather than a broken one.
enum Palette {
    static let bg = Color(red: 0.05, green: 0.05, blue: 0.07)
    static let panel = Color(red: 0.11, green: 0.11, blue: 0.14)
    static let accent = Color(red: 0.45, green: 0.62, blue: 1.0)
    static let subdued = Color.white.opacity(0.55)
}
