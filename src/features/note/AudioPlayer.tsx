import { useEffect, useMemo, useRef, useState } from "react";
import Icon from "../../components/Icon";
import { audioUrlFor } from "../../data/sessionsRepo";
import { formatDuration } from "../../data/session";

/*
 * Docked player (§09: 44px play button). Audio is streamed from Cloud Storage
 * via a download URL — desktop never has the phone's local file, so a note with
 * no storagePath is "recorded but not yet uploaded", which we say plainly
 * rather than rendering a dead player.
 */
export default function AudioPlayer({
  storagePath,
  durationMs,
  bookmarks,
}: {
  storagePath: string | null;
  durationMs: number;
  bookmarks: number[];
}) {
  const audioRef = useRef<HTMLAudioElement>(null);
  const [url, setUrl] = useState<string | null>(null);
  const [state, setState] = useState<"loading" | "ready" | "absent" | "error">("loading");
  const [playing, setPlaying] = useState(false);
  const [position, setPosition] = useState(0);
  const [length, setLength] = useState(durationMs / 1000);

  useEffect(() => {
    let alive = true;
    setState("loading");
    setUrl(null);
    setPlaying(false);
    setPosition(0);

    if (!storagePath) {
      setState("absent");
      return;
    }
    audioUrlFor(storagePath)
      .then((u) => {
        if (!alive) return;
        if (u) {
          setUrl(u);
          setState("ready");
        } else {
          setState("error");
        }
      })
      .catch(() => alive && setState("error"));

    return () => {
      alive = false;
    };
  }, [storagePath]);

  const toggle = () => {
    const el = audioRef.current;
    if (!el) return;
    if (el.paused) void el.play();
    else el.pause();
  };

  const seek = (secs: number) => {
    const el = audioRef.current;
    if (!el) return;
    el.currentTime = Math.max(0, Math.min(secs, length || 0));
    setPosition(el.currentTime);
  };

  const markers = useMemo(() => {
    if (!length) return [];
    return bookmarks
      .map((ms) => ms / 1000)
      .filter((s) => s > 0 && s <= length)
      .map((s) => ({ secs: s, pct: (s / length) * 100 }));
  }, [bookmarks, length]);

  if (state === "absent") {
    return (
      <div className="player player--flat">
        <Icon name="cloud_off" size={18} />
        <span>Audio hasn't synced from your phone yet.</span>
      </div>
    );
  }

  if (state === "error") {
    return (
      <div className="player player--flat">
        <Icon name="error" size={18} />
        <span>Couldn't load this recording.</span>
      </div>
    );
  }

  const pct = length ? (position / length) * 100 : 0;

  return (
    <div className="player">
      <button
        className="player__play"
        onClick={toggle}
        disabled={state !== "ready"}
        aria-label={playing ? "Pause" : "Play"}
      >
        <Icon name={playing ? "pause" : "play_arrow"} size={24} fill />
      </button>

      <div className="player__body">
        <div className="player__track">
          <input
            type="range"
            min={0}
            max={length || 0}
            step={0.1}
            value={position}
            onChange={(e) => seek(Number(e.target.value))}
            aria-label="Seek"
            style={{ ["--pct" as string]: `${pct}%` }}
          />
          {markers.map((m, i) => (
            <button
              key={i}
              className="player__marker"
              style={{ left: `${m.pct}%` }}
              onClick={() => seek(m.secs)}
              title={`Bookmark at ${formatDuration(m.secs * 1000)}`}
              aria-label={`Jump to bookmark at ${formatDuration(m.secs * 1000)}`}
            />
          ))}
        </div>

        <div className="player__times">
          <span>{formatDuration(position * 1000)}</span>
          <span>{formatDuration((length || durationMs / 1000) * 1000)}</span>
        </div>
      </div>

      {url && (
        <audio
          ref={audioRef}
          src={url}
          preload="metadata"
          onLoadedMetadata={(e) => {
            const d = e.currentTarget.duration;
            if (Number.isFinite(d) && d > 0) setLength(d);
          }}
          onTimeUpdate={(e) => setPosition(e.currentTarget.currentTime)}
          onPlay={() => setPlaying(true)}
          onPause={() => setPlaying(false)}
          onEnded={() => {
            setPlaying(false);
            setPosition(0);
          }}
        />
      )}
    </div>
  );
}
