import SwiftUI

struct AuthView: View {
    @EnvironmentObject private var auth: AuthService
    @State private var email = ""
    @State private var password = ""
    @State private var signingUp = false

    private var canSubmit: Bool {
        email.contains("@") && password.count >= 6 && !auth.busy
    }

    var body: some View {
        ZStack {
            Palette.bg.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Spacer(minLength: 60)
                    Text("Yap")
                        .font(.system(size: 40, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                    Text(signingUp
                         ? "Make an account."
                         : "Sign in — your notes are already here.")
                        .font(.callout)
                        .foregroundStyle(Palette.subdued)

                    VStack(spacing: 10) {
                        TextField("Email", text: $email)
                            .textContentType(.emailAddress)
                            .keyboardType(.emailAddress)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        SecureField("Password", text: $password)
                            .textContentType(signingUp ? .newPassword : .password)
                    }
                    .padding(14)
                    .background(Palette.panel, in: RoundedRectangle(cornerRadius: 14))
                    .foregroundStyle(.white)

                    if let error = auth.error {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(.orange)
                    }

                    Button {
                        Task {
                            if signingUp {
                                await auth.signUp(email: email, password: password)
                            } else {
                                await auth.signIn(email: email, password: password)
                            }
                        }
                    } label: {
                        HStack {
                            Spacer()
                            if auth.busy {
                                ProgressView().tint(.black)
                            } else {
                                Text(signingUp ? "Create account" : "Sign in")
                                    .fontWeight(.semibold)
                            }
                            Spacer()
                        }
                        .padding(.vertical, 14)
                        .background(canSubmit ? Palette.accent : Palette.accent.opacity(0.3))
                        .foregroundStyle(.black)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                    }
                    .disabled(!canSubmit)

                    Button {
                        Task { await auth.signInWithGoogle() }
                    } label: {
                        HStack {
                            Spacer()
                            Text("Continue with Google").fontWeight(.medium)
                            Spacer()
                        }
                        .padding(.vertical, 14)
                        .background(Palette.panel)
                        .foregroundStyle(.white)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                    }
                    .disabled(auth.busy)

                    HStack {
                        Button(signingUp ? "I already have an account" : "Create an account") {
                            signingUp.toggle()
                            auth.error = nil
                        }
                        Spacer()
                        if !signingUp {
                            Button("Forgot password?") {
                                Task { await auth.sendReset(email: email) }
                            }
                            .disabled(!email.contains("@") || auth.busy)
                        }
                    }
                    .font(.footnote)
                    .foregroundStyle(Palette.accent)

                    Spacer(minLength: 40)
                }
                .padding(.horizontal, 24)
            }
        }
    }
}
