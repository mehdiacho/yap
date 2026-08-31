import { useCallback, useEffect, useState } from "react";
import { call } from "./firebase.js";

/**
 * Load from a callable, with the three states every view here needs: loading, error, data.
 *
 * `payload` is stringified into the dependency list rather than compared by reference — the
 * views build fresh objects on every render, and a reference dependency would re-fetch on
 * every keystroke somewhere else on the page.
 */
export function useApi(name, payload = {}, enabled = true) {
  const key = JSON.stringify(payload);
  const [state, setState] = useState({ loading: enabled, error: null, data: null });

  const reload = useCallback(() => {
    if (!enabled) return;
    let live = true;
    setState((s) => ({ ...s, loading: true, error: null }));
    call(name, JSON.parse(key))
      .then((data) => live && setState({ loading: false, error: null, data }))
      .catch((e) => live && setState({ loading: false, error: e.message, data: null }));
    return () => {
      live = false;
    };
  }, [name, key, enabled]);

  useEffect(() => reload(), [reload]);

  return { ...state, reload };
}
