import React, { useEffect, useRef, useState } from 'react';
import WaveSurfer from 'wavesurfer.js';
import { Play, Pause } from 'lucide-react';

interface WaveformPreviewProps {
  audioBlob: Blob | null;
}

const WaveformPreview: React.FC<WaveformPreviewProps> = ({ audioBlob }) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const wavesurferRef = useRef<WaveSurfer | null>(null);
  const [isPlaying, setIsPlaying] = useState(false);

  useEffect(() => {
    if (!containerRef.current || !audioBlob) return;

    const ws = WaveSurfer.create({
      container: containerRef.current,
      waveColor: '#18181b', // zinc-900
      progressColor: '#06b6d4', // cyan-500
      cursorColor: '#06b6d4',
      height: 160,
      normalize: true,
      barWidth: 2,
      barGap: 3,
      interact: true,
    });

    const url = URL.createObjectURL(audioBlob);
    ws.load(url);

    ws.on('finish', () => setIsPlaying(false));
    wavesurferRef.current = ws;

    return () => {
      ws.destroy();
      URL.revokeObjectURL(url);
    };
  }, [audioBlob]);

  const togglePlay = () => {
    if (wavesurferRef.current) {
      wavesurferRef.current.playPause();
      setIsPlaying(!isPlaying);
    }
  };

  if (!audioBlob) return null;

  return (
    <div className="bg-zinc-950 border border-zinc-800 rounded-xl p-6 shadow-2xl relative overflow-hidden group">
      <div className="absolute top-0 left-0 w-full h-1 bg-gradient-to-r from-transparent via-cyan-500/30 to-transparent"></div>
      
      <div className="flex items-center justify-between mb-4">
        <h3 className="font-mono text-[10px] text-cyan-500/70 tracking-[0.3em] uppercase">
          Signal_Analytic_Capture
        </h3>
        <button
          onClick={togglePlay}
          className="bg-cyan-500/10 hover:bg-cyan-500/20 text-cyan-400 border border-cyan-500/30 px-4 py-1 rounded-full flex items-center gap-2 transition-all active:scale-95"
        >
          {isPlaying ? <Pause size={14} fill="currentColor" /> : <Play size={14} fill="currentColor" />}
          <span className="font-mono text-[10px] font-bold uppercase tracking-widest">
            {isPlaying ? 'Halt_Stream' : 'Execute_Audio'}
          </span>
        </button>
      </div>

      <div ref={containerRef} className="w-full opacity-80 group-hover:opacity-100 transition-opacity" />
    </div>
  );
};

export default WaveformPreview;
