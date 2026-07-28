/** The Yap mark — bars + spark, lifted from the design kit masthead. */
export default function Logo({ size = 28, radius = 8 }: { size?: number; radius?: number }) {
  return (
    <div
      style={{
        width: size,
        height: size,
        borderRadius: radius,
        background: "linear-gradient(150deg,#6D5BEA,#5B5BD6 55%,#4F46C8)",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        flex: "none",
      }}
    >
      <svg width={size * 0.62} height={size * 0.62} viewBox="0 0 100 100" aria-hidden="true">
        <rect x="20" y="42" width="9" height="16" rx="4.5" fill="#fff" opacity=".82" />
        <rect x="34" y="32" width="9" height="36" rx="4.5" fill="#fff" />
        <rect x="62" y="34" width="9" height="32" rx="4.5" fill="#fff" />
        <rect x="76" y="44" width="9" height="12" rx="4.5" fill="#fff" opacity=".82" />
        <path d="M53 18 L57 40 L66 50 L57 56 L53 82 L49 56 L40 50 L49 40 Z" fill="#fff" />
      </svg>
    </div>
  );
}
