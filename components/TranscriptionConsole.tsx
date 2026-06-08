import React, { useState } from 'react';
import { Terminal, Copy, Sparkles, RefreshCw } from 'lucide-react';
import { AudioSegment } from '../types';

interface TranscriptionConsoleProps {
  segments: AudioSegment[];
  regeneratedText: string | null;
  isRegenerating: boolean;
  isRegenEnabled: boolean;
  onManualRegenerate: () => void;
}

const TranscriptionConsole: React.FC<TranscriptionConsoleProps> = ({ 
  segments, 
  regeneratedText, 
  isRegenerating,
  isRegenEnabled,
  onManualRegenerate
}) => {
  const [activeTab, setActiveTab] = useState<'RAW' | 'REGEN'>('RAW');

  const rawFullText = segments
    .filter(s => s.status === 'COMPLETED' && s.transcript)
    .map(s => `[${new Date(s.startTime * 1000).toISOString().substr(14, 5)}] ${s.transcript}`)
    .join('\n\n');

  // Switch to REGEN tab automatically when regeneration completes
  React.useEffect(() => {
    if (regeneratedText && !isRegenerating) {
      setActiveTab('REGEN');
    }
  }, [regeneratedText, isRegenerating]);

  const copyToClipboard = async () => {
    const textToCopy = activeTab === 'RAW' ? rawFullText : regeneratedText;
    if (textToCopy) {
      try {
        if (navigator.clipboard && window.isSecureContext) {
          await navigator.clipboard.writeText(textToCopy);
        } else {
          throw new Error('Clipboard API not available');
        }
      } catch (err) {
        const textArea = document.createElement("textarea");
        textArea.value = textToCopy;
        textArea.style.position = "fixed";
        textArea.style.left = "-999999px";
        textArea.style.top = "-999999px";
        document.body.appendChild(textArea);
        textArea.focus();
        textArea.select();
        try {
          document.execCommand('copy');
        } catch (error) {
          console.error('Fallback copy failed', error);
        }
        textArea.remove();
      }
    }
  };

  const hasRawText = segments.some(s => s.status === 'COMPLETED');
  const showManualRegenBtn = !isRegenEnabled && hasRawText && !isRegenerating;

  return (
    <div className="flex flex-col h-full bg-zinc-900 border border-zinc-800 rounded-lg overflow-hidden shadow-lg mt-4">
      {/* Header / Tabs */}
      <div className="bg-zinc-950 border-b border-zinc-800 flex items-center justify-between pl-2">
        <div className="flex items-center">
            {/* Tab 1: Raw */}
            <button
                onClick={() => setActiveTab('RAW')}
                className={`flex items-center gap-2 px-4 py-3 font-mono text-xs font-bold tracking-widest border-r border-zinc-800 transition-colors ${
                    activeTab === 'RAW' 
                    ? 'bg-zinc-900 text-cyan-500 shadow-[inset_0_2px_0_0_#06b6d4]' 
                    : 'text-zinc-500 hover:text-zinc-300 hover:bg-zinc-900/50'
                }`}
            >
                <Terminal size={14} />
                RAW_TRANSCRIPT
            </button>
            
            {/* Tab 2: Regen */}
            <button
                onClick={() => setActiveTab('REGEN')}
                disabled={!regeneratedText && !isRegenerating}
                className={`flex items-center gap-2 px-4 py-3 font-mono text-xs font-bold tracking-widest border-r border-zinc-800 transition-colors ${
                    activeTab === 'REGEN' 
                    ? 'bg-zinc-900 text-cyan-500 shadow-[inset_0_2px_0_0_#06b6d4]' 
                    : 'text-zinc-500 hover:text-zinc-300 hover:bg-zinc-900/50 disabled:opacity-50 disabled:cursor-not-allowed'
                }`}
            >
                <Sparkles size={14} />
                REGEN_OUTPUT
            </button>
        </div>

        <div className="flex items-center gap-4 pr-3">
             {showManualRegenBtn && (
                <button
                    onClick={onManualRegenerate}
                    className="flex items-center gap-2 px-3 py-1.5 rounded border border-zinc-800 text-zinc-400 font-mono text-xs hover:text-cyan-400 hover:border-cyan-400 transition-all uppercase"
                >
                    <RefreshCw size={12} /> Execute_Regen
                </button>
            )}

            <button 
                onClick={copyToClipboard}
                className="text-zinc-500 hover:text-cyan-400 transition-colors flex items-center gap-1 text-xs font-mono uppercase"
            >
                <Copy size={12} /> COPY_BUFFER
            </button>
        </div>
      </div>
      
      {/* Content Area */}
      <div className="flex-1 p-4 overflow-y-auto font-mono text-sm leading-relaxed text-zinc-300 bg-[#0c0c0e]">
        {activeTab === 'RAW' ? (
             <>
                {segments.length === 0 ? (
                <span className="text-zinc-600 italic">No data stream detected. Waiting for input...</span>
                ) : (
                segments.map((seg) => (
                    <div key={seg.id} className="mb-4 animate-in fade-in duration-500">
                        {seg.status === 'COMPLETED' ? (
                            <>
                            <span className="text-cyan-700 select-none mr-2">
                                [{new Date(seg.startTime * 1000).toISOString().substr(14, 5)}]
                            </span>
                            <span className="text-zinc-300">{seg.transcript}</span>
                            </>
                        ) : seg.status === 'PROCESSING' ? (
                            <div className="flex items-center gap-2 text-zinc-600">
                                <span className="animate-pulse">>> DECRYPTING_AUDIO_PACKET_{seg.id.split('-')[0]}...</span>
                            </div>
                        ) : null}
                    </div>
                ))
                )}
             </>
        ) : (
            // Regen View
            <div className="h-full">
                {isRegenerating ? (
                     <div className="flex flex-col items-center justify-center h-full text-cyan-500 gap-4">
                        <RefreshCw size={32} className="animate-spin" />
                        <span className="font-mono text-sm tracking-widest uppercase animate-pulse">Processing_Contextual_Optimization...</span>
                     </div>
                ) : (
                    <div className="animate-in fade-in duration-700 whitespace-pre-wrap">
                        {regeneratedText || <span className="text-zinc-600 italic">Awaiting regeneration command...</span>}
                    </div>
                )}
            </div>
        )}
        <div className="h-4" /> {/* Padding bottom */}
      </div>
    </div>
  );
};

export default TranscriptionConsole;