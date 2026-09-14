import * as admin from "firebase-admin";

/**
 * The category and topic names a person has already used, so a new note lands under them.
 *
 * formalize used to see one transcript and nothing else, so every note got a freshly invented
 * topicCluster. By 2026-09-07, 73 of 208 notes were "General" and most of the rest were
 * singletons; one day in August produced seven spellings of one topic ("Build for Good UX",
 * "Building with Good UX Series", …), and categories split the same way ("Feedback" / "User
 * Feedback" / "App Feedback"). A label nobody else shares is not a grouping at all.
 *
 * Two defences, because a prompt alone is a request, not a guarantee: the names go into the
 * prompt with reuse as the default, and whatever comes back is snapped onto an existing name
 * when it differs only in case, spacing or punctuation.
 */
export interface Labels {
  categories: string[];
  topics: string[];
}

export const NO_LABELS: Labels = { categories: [], topics: [] };

/** Recent notes are enough to know someone's vocabulary; this is a select(), not full docs. */
const SCAN = 500;
const MAX_CATEGORIES = 20;
const MAX_TOPICS = 40;

/** Never throws. Labels make the result better; failing to fetch them must not fail a note. */
export async function existingLabels(uid: string): Promise<Labels> {
  try {
    const snap = await admin
      .firestore()
      .collection("users")
      .doc(uid)
      .collection("sessions")
      .orderBy("createdAt", "desc")
      .limit(SCAN)
      .select("category", "topicCluster", "trashedAt")
      .get();

    const categories = new Tally();
    const topics = new Tally();
    for (const d of snap.docs) {
      if (d.get("trashedAt") != null) continue;
      categories.add(d.get("category"));
      topics.add(d.get("topicCluster"));
    }
    return { categories: categories.top(MAX_CATEGORIES), topics: topics.top(MAX_TOPICS) };
  } catch (e: any) {
    console.warn("existingLabels failed (non-fatal)", { uid, message: e?.message });
    return NO_LABELS;
  }
}

/** "Good UX Series", "good-ux series " and "Good UX  Series" are one label. */
function key(label: string): string {
  return label.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, " ").trim();
}

class Tally {
  // Keyed by normalized form. Docs arrive newest first, so the spelling kept is the most
  // recent one — if the person corrected a label by hand, that is the one that wins.
  private readonly m = new Map<string, { name: string; count: number }>();

  add(v: unknown) {
    if (typeof v !== "string") return;
    const name = v.trim();
    // "General" is the absence of a topic. Offering it as one to reuse would teach the model
    // that it is a safe place to put everything.
    if (!name || key(name) === "general") return;
    const k = key(name);
    const e = this.m.get(k);
    if (e) e.count += 1;
    else this.m.set(k, { name, count: 1 });
  }

  top(n: number): string[] {
    return [...this.m.values()].sort((a, b) => b.count - a.count).slice(0, n).map((e) => e.name);
  }
}

/** The prompt block. Empty for a first note — there is nothing to be consistent with yet. */
export function labelSection(labels: Labels): string {
  if (!labels.categories.length && !labels.topics.length) return "";
  const list = (xs: string[]) => (xs.length ? xs.map((x) => JSON.stringify(x)).join(", ") : "(none yet)");
  return `
### Labels this person already uses (most-used first)
Categories: ${list(labels.categories)}
Topic clusters: ${list(labels.topics)}

Category and TopicCluster must reuse one of these, spelled exactly as listed, whenever the note plausibly belongs under it. The same subject has to land under the same name every time, or the labels are useless for finding anything. Only when none fits: use one of the example categories above, and for TopicCluster either "General" or a new name for the project or theme — short and general enough that later notes on the same subject can share it ("Good UX Series", not "Building with Good UX Part 3"). Never create a near-duplicate of a listed label.
`;
}

/** Replace a returned label with the existing spelling when they differ only cosmetically. */
export function snapLabels(result: { category: string; topicCluster: string }, labels: Labels): void {
  const snap = (v: string, known: string[]) => known.find((k) => key(k) === key(v)) ?? v;
  if (result.category) result.category = snap(result.category, labels.categories);
  if (result.topicCluster) result.topicCluster = snap(result.topicCluster, labels.topics);
}
