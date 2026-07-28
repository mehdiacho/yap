import { useMemo, useRef, type KeyboardEvent } from "react";
import Icon from "../../components/Icon";
import StatusChip from "./StatusChip";
import { useFilteredSessions, useGroupedSessions } from "../../data/hooks";
import { displayText, formatDuration, formatTimestamp, type Session } from "../../data/session";

export type Filter = "all" | "pinned" | "drafts" | "trash";

const FILTERS: { id: Filter; label: string }[] = [
  { id: "all", label: "All" },
  { id: "pinned", label: "Pinned" },
  { id: "drafts", label: "Drafts" },
  { id: "trash", label: "Trash" },
];

interface Props {
  sessions: Session[];
  loading: boolean;
  error: Error | null;
  selectedId: string | null;
  onSelect: (id: string) => void;
  search: string;
  onSearch: (v: string) => void;
  filter: Filter;
  onFilter: (f: Filter) => void;
  searchRef: React.RefObject<HTMLInputElement | null>;
}

function snippet(s: Session): string {
  const text = displayText(s)
    .replace(/^#{1,6}\s+/gm, "")
    .replace(/[*_`>[\]]/g, "")
    .replace(/\s+/g, " ")
    .trim();
  return text.length > 120 ? `${text.slice(0, 120)}…` : text || "No text yet";
}

export default function LibraryList(p: Props) {
  const listRef = useRef<HTMLDivElement>(null);

  const scoped = useMemo(() => {
    switch (p.filter) {
      case "pinned":
        return p.sessions.filter((s) => s.pinned);
      case "drafts":
        return p.sessions.filter((s) => !s.formattedText?.trim());
      default:
        return p.sessions;
    }
  }, [p.sessions, p.filter]);

  const filtered = useFilteredSessions(scoped, p.search);
  const groups = useGroupedSessions(filtered);

  // §09: ↑/↓ move through the list without leaving the search field.
  const onKeyDown = (e: KeyboardEvent) => {
    if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
    e.preventDefault();
    const flat = groups.flatMap((g) => g.items);
    if (!flat.length) return;
    const i = flat.findIndex((s) => s.id === p.selectedId);
    const next = e.key === "ArrowDown" ? Math.min(i + 1, flat.length - 1) : Math.max(i - 1, 0);
    p.onSelect(flat[i === -1 ? 0 : next].id);
  };

  return (
    <div className="list" onKeyDown={onKeyDown}>
      <div className="list__head chrome">
        <div className="search">
          <Icon name="search" size={19} />
          <input
            ref={p.searchRef}
            value={p.search}
            onChange={(e) => p.onSearch(e.target.value)}
            placeholder="Search notes"
            aria-label="Search notes"
          />
          {p.search && (
            <button onClick={() => p.onSearch("")} aria-label="Clear search">
              <Icon name="close" size={17} />
            </button>
          )}
        </div>

        <div className="chips">
          {FILTERS.map((f) => (
            <button
              key={f.id}
              className={`chip${p.filter === f.id ? " is-on" : ""}`}
              onClick={() => p.onFilter(f.id)}
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>

      <div className="list__scroll" ref={listRef}>
        {p.loading && <ListSkeleton />}

        {!p.loading && p.error && (
          <div className="list__state">
            <Icon name="cloud_off" size={30} />
            <p className="list__state-title">Couldn't load your notes</p>
            <p className="list__state-body">{p.error.message}</p>
          </div>
        )}

        {!p.loading && !p.error && filtered.length === 0 && (
          <EmptyState filter={p.filter} search={p.search} total={p.sessions.length} />
        )}

        {!p.loading &&
          !p.error &&
          groups.map((g) => (
            <section key={g.label}>
              <h2 className="eyebrow">{g.label}</h2>
              {g.items.map((s) => (
                <Card
                  key={s.id}
                  session={s}
                  selected={s.id === p.selectedId}
                  onClick={() => p.onSelect(s.id)}
                />
              ))}
            </section>
          ))}
      </div>
    </div>
  );
}

function Card({
  session: s,
  selected,
  onClick,
}: {
  session: Session;
  selected: boolean;
  onClick: () => void;
}) {
  return (
    <button className={`card${selected ? " is-selected" : ""}`} onClick={onClick}>
      <div className="card__top">
        <span className="card__title">{s.title}</span>
        {s.pinned && <Icon name="keep" size={15} fill className="card__pin" />}
      </div>

      <p className="card__snippet">{snippet(s)}</p>

      <div className="card__meta">
        <StatusChip session={s} />
        {s.durationMs > 0 && (
          <span className="card__metaitem">
            <Icon name="schedule" size={13} />
            {formatDuration(s.durationMs)}
          </span>
        )}
        <span className="card__metaitem card__metaitem--time">
          {formatTimestamp(s.createdAt)}
        </span>
        {/* Availability glyph — cloud copy present vs online-only, matching the phone. */}
        <Icon
          name={s.storagePath ? "cloud_done" : "cloud_off"}
          size={14}
          className="card__cloud"
        />
      </div>
    </button>
  );
}

function EmptyState({
  filter,
  search,
  total,
}: {
  filter: Filter;
  search: string;
  total: number;
}) {
  if (search) {
    return (
      <div className="list__state">
        <Icon name="search_off" size={30} />
        <p className="list__state-title">No matches for “{search}”</p>
        <p className="list__state-body">Try a shorter phrase or a different word.</p>
      </div>
    );
  }
  if (filter === "trash") {
    return (
      <div className="list__state">
        <Icon name="delete" size={30} />
        <p className="list__state-title">Trash is empty</p>
        <p className="list__state-body">Deleted notes rest here for about 30 days.</p>
      </div>
    );
  }
  if (total === 0) {
    return (
      <div className="list__state">
        <Icon name="mic" size={30} />
        <p className="list__state-title">No notes yet</p>
        <p className="list__state-body">
          Record on your phone and it appears here within seconds.
        </p>
      </div>
    );
  }
  return (
    <div className="list__state">
      <Icon name="filter_alt_off" size={30} />
      <p className="list__state-title">Nothing in this filter</p>
      <p className="list__state-body">Switch back to All to see every note.</p>
    </div>
  );
}

function ListSkeleton() {
  return (
    <div className="skel" aria-hidden="true">
      {Array.from({ length: 6 }).map((_, i) => (
        <div className="skel__card" key={i}>
          <div className="skel__line skel__line--title" />
          <div className="skel__line" />
          <div className="skel__line skel__line--short" />
        </div>
      ))}
    </div>
  );
}
