import React, { useEffect, useRef, useState } from 'react';
import WaveSurfer from 'wavesurfer.js';
import { Play, Pause, Scissors } from 'lucide-react';

interface WaveformDisplayProps {
  audioBlob: Blob | null;
  onTimeUpdate: (time: number) => void;
  onSplit: (time: number) => void;
  regions: { start: number; end: number; id: string }[];
}

const WaveformDisplay: React.FC<WaveformDisplayProps> = ({ audioBlob, onTimeUpdate, onSplit, regions }) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const wavesurferRef = useRef<WaveSurfer | null>(null);
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);

  useEffect(() => {
    if (!containerRef.current || !audioBlob) return;

    const ws = WaveSurfer.create({
      container: containerRef.current,
      waveColor: '#27272a', // zinc-800
      progressColor: '#06b6d4', // cyan-500
      cursorColor: '#ef4444', // red-500
      height: 128,
      normalize: true,
      barWidth: 2,
      barGap: 3,
    });

    const url = URL.createObjectURL(audioBlob);
    ws.load(url);

    ws.on('ready', () => {
      setDuration(ws.getDuration());
    });

    ws.on('audioprocess', (time) => {
      const t = ws.getCurrentTime();
      setCurrentTime(t);
      onTimeUpdate(t);
    });

    ws.on('interaction', () => {
       const t = ws.getCurrentTime();
       setCurrentTime(t);
       onTimeUpdate(t);
    });

    ws.on('finish', () => setIsPlaying(false));

    wavesurferRef.current = ws;

    return () => {
      ws.destroy();
      URL.revokeObjectURL(url);
    };
  }, [audioBlob]); // Re-init if file changes

  const togglePlay = () => {
    if (wavesurferRef.current) {
      if (isPlaying) {
        wavesurferRef.current.pause();
      } else {
        wavesurferRef.current.play();
      }
      setIsPlaying(!isPlaying);
    }
  };

  const handleSplit = () => {
    if (wavesurferRef.current) {
      onSplit(wavesurferRef.current.getCurrentTime());
    }
  };

  const formatTime = (time: number) => {
    const mins = Math.floor(time / 60);
    const secs = Math.floor(time % 60);
    const ms = Math.floor((time % 1) * 100);
    return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}.${ms.toString().padStart(2, '0')}`;
  };

  return (
    <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4 shadow-lg flex flex-col gap-4">
      <div className="flex items-center justify-between border-b border-zinc-800 pb-2 mb-2">
        <h3 className="font-mono text-xs text-cyan-500 uppercase tracking-widest flex items-center gap-2">
          Waveform_Visualizer
        </h3>
        <span className="font-mono text-xs text-zinc-500">
          {formatTime(currentTime)} / {formatTime(duration)}
        </span>
      </div>

      <div ref={containerRef} className="w-full relative" />

      <div className="flex items-center gap-4 mt-2">
        <button
          onClick={togglePlay}
          className="bg-cyan-900/30 hover:bg-cyan-900/50 text-cyan-400 border border-cyan-800 p-2 rounded transition-all flex items-center gap-2 group"
        >
          {isPlaying ? <Pause size={16} /> : <Play size={16} />}
          <span className="font-mono text-xs font-bold uppercase group-hover:text-cyan-300">
            {isPlaying ? 'Halt' : 'Execute'}
          </span>
        </button>

        <button
          onClick={handleSplit}
          className="bg-zinc-800 hover:bg-zinc-700 text-zinc-300 border border-zinc-700 p-2 rounded transition-all flex items-center gap-2 group ml-auto"
        >
          <Scissors size={16} className="text-red-500" />
          <span className="font-mono text-xs font-bold uppercase group-hover:text-white">
            Splice_At_Cursor
          </span>
        </button>
      </div>
      
      {/* Segment Visualization Overlay (Simulated via CSS gradients or just markers) */}
      <div className="h-2 w-full bg-zinc-950 flex mt-1 rounded overflow-hidden">
        {regions.map((r, idx) => (
           <div 
             key={r.id} 
             style={{ 
               width: `${((r.end - r.start) / duration) * 100}%`,
               backgroundColor: idx % 2 === 0 ? '#1e293b' : '#334155' 
             }}
             className="h-full border-r border-zinc-900"
             title={`Seg ${idx}: ${r.start.toFixed(1)}s - ${r.end.toFixed(1)}s`}
           />
        ))}
      </div>
    </div>
  );
};

export default WaveformDisplay;