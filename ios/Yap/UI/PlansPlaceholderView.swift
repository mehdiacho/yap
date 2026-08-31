import SwiftUI

/// Plans, without a store attached.
///
/// This is a placeholder ON PURPOSE, and the reason is worth stating plainly rather than
/// hiding behind a "coming soon": In-App Purchase cannot be built at all without a paid
/// Apple Developer account. Products live in App Store Connect, which does not exist for a
/// free Apple ID, and StoreKit returns an empty product list with nothing to buy. There is
/// no sandbox, no test mode, and no offline stub that proves anything.
///
/// Google Play Billing is not an alternative here either — it is delivered by Play Services,
/// which does not exist on iOS. The Android app's billing code has no iOS counterpart.
///
/// So this screen shows the plans as information and sends anyone who wants to change one to
/// the Android app, where the purchase actually works. Entitlement is read from the same
/// Firestore user document either way, so a plan bought on Android is already in force here.
struct PlansPlaceholderView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                Palette.bg.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        Text("Your plan is shared across devices.")
                            .font(.headline)
                            .foregroundStyle(.white)
                        Text("""
                        Whatever you're subscribed to on Android applies here — it's the same \
                        account and the same monthly allowance.
                        """)
                        .font(.callout)
                        .foregroundStyle(Palette.subdued)

                        Divider().overlay(Color.white.opacity(0.1))

                        Text("Changing your plan")
                            .font(.headline)
                            .foregroundStyle(.white)
                        Text("""
                        Purchases aren't available in this build. Subscribe or cancel in the \
                        Android app and it takes effect here immediately.
                        """)
                        .font(.callout)
                        .foregroundStyle(Palette.subdued)

                        Spacer(minLength: 20)
                    }
                    .padding(20)
                }
            }
            .navigationTitle("Plans")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .tint(Palette.accent)
        .preferredColorScheme(.dark)
    }
}
