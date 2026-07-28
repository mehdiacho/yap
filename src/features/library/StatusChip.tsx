import Icon from "../../components/Icon";
import { isRefined, type Session } from "../../data/session";

type Tone = "ok" | "warn" | "busy" | "neutral" | "danger";

interface Spec {
  label: string;
  icon: string;
  tone: Tone;
}

/*
 * The phone's status-chip system, reused verbatim. `status` comes straight from
 * the session doc (DRAFT | TRANSCRIBING | DONE | FAILED); a DONE note that has
 * no formattedText is a draft the cloud hasn't polished yet, which the phone
 * surfaces as "Not refined".
 */
function specFor(s: Session): Spec {
  switch (s.status.toUpperCase()) {
    case "TRANSCRIBING":
      return { label: "Polishing", icon: "autorenew", tone: "busy" };
    case "FAILED":
      return { label: "Failed", icon: "error", tone: "danger" };
    case "DRAFT":
      return { label: "Draft", icon: "edit_note", tone: "warn" };
    default:
      return isRefined(s)
        ? { label: s.category || "Note", icon: "auto_awesome", tone: "ok" }
        : { label: "Not refined", icon: "edit_note", tone: "warn" };
  }
}

export default function StatusChip({ session }: { session: Session }) {
  const { label, icon, tone } = specFor(session);
  return (
    <span className={`status status--${tone}`}>
      <Icon name={icon} size={13} className={tone === "busy" ? "spin" : undefined} />
      {label}
    </span>
  );
}
