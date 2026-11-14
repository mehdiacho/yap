
import React, { useEffect, useRef, useState } from 'react';
import { Terminal as TerminalIcon, Download, CheckCircle, Cpu, Copy, Check, FileText } from 'lucide-react';
import { SystemLog } from '../types';

interface TerminalOutputProps {
  logs: SystemLog[];
  transcript: string;
  isProcessing: boolean;
  onDownload: () => void;
}

const TerminalOutput: React.FC<TerminalOutputProps> = ({ logs, transcript, isProcessing, onDownload }) => {
  const terminalEndRef = useRef<HTMLDivElement>(null);
  const [copyFeedback, setCopyFeedback] = useState(false);

  useEffect(() => {
    terminalEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [logs, transcript]);

  const handleCopy = async () => {
    if (!transcript) return;
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(transcript);
      } else {
        throw new Error('Clipboard API not available');
      }
    } catch (err) {
      const textArea = document.createElement("textarea");
      textArea.value = transcript;
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
    setCopyFeedback(true);
    setTimeout(() => setCopyFeedback(false), 2000);
  };

  // Simple logic to check if we are dealing with VTT content
  const isVtt = transcript.startsWith('WEBVTT') || transcript.trim().match(/^\d+\r?\n\d{2}:\d{2}:\d{2}\.\d{3}/);

  return (
    <div className="flex flex-col h-full bg-[#0c0c0e] border border-zinc-800 rounded-xl overflow-hidden shadow-2xl">
      {/* Header */}
      <div className="bg-zinc-900/50 border-b border-zinc-800 px-4 py-3 flex flex-col sm:flex-row sm:items-center justify-between gap-3 sm:gap-0">
        <div className="flex flex-wrap items-center gap-2 sm:gap-3">
          <TerminalIcon size={16} className="text-cyan-500 shrink-0" />
          <span className="font-mono text-[10px] sm:text-xs font-bold text-zinc-400 tracking-widest uppercase">
            System_Output_Buffer
          </span>
          {isVtt && !isProcessing && (
            <span className="font-mono text-[8px] sm:text-[9px] bg-cyan-500/10 text-cyan-400 px-2 py-0.5 rounded border border-cyan-500/20 uppercase tracking-tighter shrink-0">
              VTT_STREAM_DETECTED
            </span>
          )}
        </div>
        <div className="flex items-center justify-between sm:justify-end gap-2 w-full sm:w-auto">
          {transcript && !isProcessing ? (
            <div className="flex items-center gap-2 animate-in fade-in duration-300 w-full sm:w-auto">
               <button
                onClick={handleCopy}
                className="flex-1 sm:flex-none justify-center flex items-center gap-2 px-3 py-1.5 sm:py-1 bg-zinc-800 text-cyan-500 border border-zinc-700 rounded font-mono text-[10px] font-bold uppercase hover:bg-zinc-700 transition-all"
                title="Copy to Clipboard"
              >
                {copyFeedback ? <Check size={12} /> : <Copy size={12} />}
                {copyFeedback ? 'Copied' : 'Copy'}
              </button>
              <button
                onClick={onDownload}
                className="flex-1 sm:flex-none justify-center flex items-center gap-2 px-3 py-1.5 sm:py-1 bg-cyan-500 text-black rounded font-mono text-[10px] font-bold uppercase hover:bg-cyan-400 transition-all shadow-[0_0_15px_rgba(6,182,212,0.4)]"
              >
                <Download size={12} />
                Export
              </button>
            </div>
          ) : (
            <div />
          )}
          <div className={`w-2 h-2 rounded-full shrink-0 ${isProcessing ? 'bg-cyan-500 animate-pulse' : 'bg-zinc-700'}`}></div>
        </div>
      </div>

      {/* Terminal Area */}
      <div className="flex-1 p-4 sm:p-6 font-mono text-[10px] sm:text-xs overflow-y-auto custom-scrollbar flex flex-col gap-4">
        {/* Logs */}
        <div className="flex flex-col gap-1.5 sm:gap-1 text-zinc-500">
          {logs.map((log) => (
            <div key={log.id} className="flex gap-3 animate-in fade-in slide-in-from-left-1 duration-300">
              <span className="text-zinc-700 whitespace-nowrap">[{log.timestamp}]</span>
              <span className={`
                ${log.type === 'PROCESS' ? 'text-cyan-600' : ''}
                ${log.type === 'SUCCESS' ? 'text-green-500' : ''}
                ${log.type === 'ERROR' ? 'text-red-500' : ''}
                ${log.type === 'INFO' ? 'text-zinc-500' : ''}
               uppercase font-bold`}>
                {log.type}:
              </span>
              <span className="text-zinc-400">{log.message}</span>
            </div>
          ))}
          {isProcessing && (
            <div className="flex gap-3 text-cyan-500 animate-pulse">
              <span className="text-zinc-700">[{new Date().toLocaleTimeString([], { hour12: false })}]</span>
              <span className="font-bold">PROCESSING:</span>
              <span className="flex items-center gap-2">
                <Cpu size={12} className="animate-spin" />
                Awaiting_Neural_Response...
              </span>
            </div>
          )}
        </div>

        {/* Transcript Output */}
        {transcript && (
          <div className="mt-4 pt-4 border-t border-zinc-800/50">
            <div className="text-cyan-500/50 mb-3 font-bold flex items-center gap-2 tracking-[0.2em] uppercase">
              <FileText size={14} />
              {isVtt ? 'Decoded_Subtitles' : 'Decoded_Transcript'}
            </div>
            <div className={`text-zinc-200 text-sm leading-relaxed whitespace-pre-wrap selection:bg-cyan-500 selection:text-black ${isVtt ? 'font-mono text-xs bg-zinc-950/50 p-4 rounded border border-zinc-900' : ''}`}>
              {transcript}
            </div>
          </div>
        )}
        <div ref={terminalEndRef} />
      </div>
    </div>
  );
};

export default TerminalOutput;
