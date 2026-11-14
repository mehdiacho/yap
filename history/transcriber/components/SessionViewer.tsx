import React, { useRef, useState, useEffect } from 'react';
import { TranscriptionSession, updateSessionInFirestore, getAllSessions } from '../services/firebaseService';
import { processSessionMetadataAndFormatting, reviewAndPropagateEdits } from '../services/geminiService';
import { generateSessionPDF } from '../services/pdfGenerator';
import { 
  Download, PlayCircle, PauseCircle, FileText, Calendar, Tag, HardDriveDownload, 
  ArrowLeft, RefreshCw, Loader2, ChevronLeft, ChevronRight, Sparkles, Copy, Check, Edit3, AlertTriangle 
} from 'lucide-react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

interface TextBlockState {
  versions: string[];
  activeIndex: number;
  flagged?: boolean;
  warningReason?: string;
}

export const SessionViewer: React.FC<{
  session: TranscriptionSession | null;
  onBack?: () => void;
}> = ({ session, onBack }) => {
  const [isPlaying, setIsPlaying] = useState(false);
  const [viewMode, setViewMode] = useState<'formatted' | 'raw'>('formatted');
  const [isFormalizing, setIsFormalizing] = useState(false);
  const audioRef = useRef<HTMLAudioElement>(null);

  // Feature 2 & 3: Inline Block-Level Version History
  const [blocks, setBlocks] = useState<TextBlockState[]>([]);
  const [selectionInfo, setSelectionInfo] = useState<{ selectedText: string; blockIndex: number; x: number; y: number } | null>(null);
  const [editMode, setEditMode] = useState(false);
  const [tempEditText, setTempEditText] = useState('');

  // Feature 1: Export Options Feedback
  const [copySuccess, setCopySuccess] = useState(false);
  const [isReviewing, setIsReviewing] = useState(false);

  // Load and parse blocks whenever session or view mode changes
  useEffect(() => {
    if (!session) {
      setBlocks([]);
      return;
    }

    const activeText = viewMode === 'formatted'
      ? (session.formatted_text || '')
      : (session.raw_text || session.transcript || '');

    const historyField = viewMode === 'formatted'
      ? session.block_history_formatted
      : session.block_history_raw;

    if (historyField && historyField.length > 0) {
      setBlocks(historyField);
    } else if (activeText.trim()) {
      const rawBlocks = activeText.split(/\n\n+/).filter(b => b.trim().length > 0);
      const parsed = rawBlocks.map(b => ({
        versions: [b],
        activeIndex: 0,
        flagged: false,
        warningReason: ''
      }));
      setBlocks(parsed);
    } else {
      setBlocks([]);
    }

    // Reset selection overlays
    setSelectionInfo(null);
    setEditMode(false);
    setTempEditText('');
  }, [session, viewMode]);

  if (!session) {
    return (
      <div className="h-full flex items-center justify-center border border-zinc-800 rounded-2xl bg-zinc-900/20 shadow-2xl relative overflow-hidden p-8">
        <div className="flex flex-col items-center gap-4 text-zinc-600">
          <FileText size={48} className="opacity-20" />
          <span className="font-mono text-xs uppercase tracking-widest">NO_SESSION_SELECTED</span>
        </div>
      </div>
    );
  }

  const togglePlay = () => {
    if (!audioRef.current) return;
    if (isPlaying) {
      audioRef.current.pause();
    } else {
      audioRef.current.play();
    }
    setIsPlaying(!isPlaying);
  };

  const handleManualFormalize = async () => {
    if (!session.raw_text && !session.transcript) return;
    try {
      setIsFormalizing(true);
      const recentSessions = await getAllSessions();
      const advancedMetadata = await processSessionMetadataAndFormatting(session.raw_text || session.transcript, recentSessions);
      
      const updates = {
        formatted_text: advancedMetadata.formatted_text,
        category: advancedMetadata.category,
        topicCluster: advancedMetadata.topicCluster,
        name: advancedMetadata.name,
        description: advancedMetadata.description,
        tags: advancedMetadata.tags,
        is_successful: true,
        // Reset block history to reload on formatting update
        block_history_formatted: [],
        block_history_raw: []
      };
      
      if (session.id) {
        await updateSessionInFirestore(session.id, updates);
        Object.assign(session, updates);
        setViewMode('formatted');
      }
    } catch (err) {
      console.error("Failed to manual formalize:", err);
      alert("Formalization failed. Please check your API Key and network.");
    } finally {
      setIsFormalizing(false);
    }
  };

  // Feature 1: Exporting visually accurate PDF
  const handleDownloadPDF = () => {
    generateSessionPDF(session);
  };

  // Feature 1: One-click Markdown copy and paste
  const handleCopyMarkdown = () => {
    const mdText = viewMode === 'formatted' && session.formatted_text 
      ? session.formatted_text 
      : (session.raw_text || session.transcript);
    
    navigator.clipboard.writeText(mdText || '');
    setCopySuccess(true);
    setTimeout(() => setCopySuccess(false), 2000);
  };

  const downloadTranscript = () => {
    const mdText = viewMode === 'formatted' && session.formatted_text 
      ? session.formatted_text 
      : (session.raw_text || session.transcript);
    
    const blob = new Blob([mdText || ''], { type: 'text/markdown' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${session.name.replace(/\s+/g, '_')}_transcript.md`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  // Feature 2: Highlighting Text & inline Edit triggers
  const handleTextSelection = (blockIndex: number, event: React.MouseEvent) => {
    // If we're already currently editing, don't disrupt typing
    if (editMode) return;

    const selection = window.getSelection();
    if (!selection || selection.rangeCount === 0) return;
    
    const selectedText = selection.toString().trim();
    if (selectedText.length > 0) {
      const range = selection.getRangeAt(0);
      const rects = range.getClientRects();
      if (rects.length > 0) {
        const rect = rects[0];
        setSelectionInfo({
          selectedText,
          blockIndex,
          x: rect.left + rect.width / 2,
          y: rect.top - 12
        });
      }
    } else {
      // Clear overlay if clicked outside selection
      setSelectionInfo(null);
    }
  };

  const handleSaveEdit = async () => {
    if (!selectionInfo) return;
    const { blockIndex, selectedText } = selectionInfo;
    
    const block = blocks[blockIndex];
    if (!block) return;

    const currentText = block.versions[block.activeIndex];
    // Replace the specific occurrence in the text
    const newText = currentText.replace(selectedText, tempEditText);

    const updatedBlocks = [...blocks];
    updatedBlocks[blockIndex] = {
      ...block,
      versions: [...block.versions, newText],
      activeIndex: block.versions.length,
      flagged: false, // reset audit status upon manual rewrite
      warningReason: ''
    };

    setBlocks(updatedBlocks);
    await saveBlocks(updatedBlocks);

    // Reset selection indicators
    setSelectionInfo(null);
    setEditMode(false);
    setTempEditText('');
  };

  const handlePrevVersion = (blockIndex: number) => {
    const updatedBlocks = [...blocks];
    if (updatedBlocks[blockIndex].activeIndex > 0) {
      updatedBlocks[blockIndex].activeIndex--;
      setBlocks(updatedBlocks);
      saveBlocks(updatedBlocks);
    }
  };

  const handleNextVersion = (blockIndex: number) => {
    const updatedBlocks = [...blocks];
    if (updatedBlocks[blockIndex].activeIndex < updatedBlocks[blockIndex].versions.length - 1) {
      updatedBlocks[blockIndex].activeIndex++;
      setBlocks(updatedBlocks);
      saveBlocks(updatedBlocks);
    }
  };

  const saveBlocks = async (updatedBlocks: TextBlockState[]) => {
    const masterText = updatedBlocks.map(b => b.versions[b.activeIndex]).join('\n\n');
    const updates: any = viewMode === 'formatted'
      ? { formatted_text: masterText }
      : { raw_text: masterText };
    
    if (viewMode === 'formatted') {
      updates.block_history_formatted = updatedBlocks;
    } else {
      updates.block_history_raw = updatedBlocks;
    }

    if (session.id) {
      await updateSessionInFirestore(session.id, updates);
      Object.assign(session, updates);
    }
  };

  // Feature 2: Smart "Review" Propagation System
  const handleSmartReview = async () => {
    const editedCount = blocks.filter(b => b.versions.length > 1).length;
    if (editedCount === 0) return;

    try {
      setIsReviewing(true);
      const results = await reviewAndPropagateEdits(blocks);

      if (results.length === 0) {
        alert("Smart Review Completed: No further matching occurrences were found to propagate.");
        return;
      }

      setBlocks(prev => {
        const nextBlocks = [...prev];
        let changedCount = 0;

        results.forEach(item => {
          const idx = item.blockIndex;
          if (idx >= 0 && idx < nextBlocks.length) {
            const currentBlock = nextBlocks[idx];
            const currentActiveText = currentBlock.versions[currentBlock.activeIndex];

            if (currentActiveText !== item.newText) {
              changedCount++;
              nextBlocks[idx] = {
                ...currentBlock,
                versions: [...currentBlock.versions, item.newText],
                activeIndex: currentBlock.versions.length,
                flagged: item.flagged || false,
                warningReason: item.reason || ''
              };
            } else if (item.flagged) {
              // Same text but flag status updated by Gemini audit
              nextBlocks[idx] = {
                ...currentBlock,
                flagged: true,
                warningReason: item.reason || ''
              };
            }
          }
        });

        saveBlocks(nextBlocks);
        
        // Brief log statement
        setTimeout(() => {
          alert(`Smart Review execution finished: Successfully updated ${changedCount} matching blocks and analyzed semantic meaning limits.`);
        }, 100);

        return nextBlocks;
      });

    } catch (err) {
      console.error("Smart Review execution error:", err);
      alert("Neural smart search execution failed. Check process keys.");
    } finally {
      setIsReviewing(false);
    }
  };

  const hasTimelineChanges = blocks.some(b => b.versions.length > 1);

  return (
    <div className="h-full flex flex-col border border-zinc-800 rounded-2xl bg-zinc-900/40 shadow-2xl relative">
      
      {/* Selection floating widget */}
      {selectionInfo && (
        <div 
          style={{ 
            position: 'fixed', 
            left: `${selectionInfo.x}px`, 
            top: `${selectionInfo.y}px` 
          }}
          className="fixed z-[100] bg-zinc-950/95 backdrop-blur border border-cyan-500/50 shadow-[0_0_20px_rgba(6,182,212,0.3)] rounded-lg p-2 flex flex-col gap-1.5 -translate-x-1/2 -translate-y-full animate-in fade-in zoom-in-95 duration-100"
        >
          {!editMode ? (
            <button
              onClick={() => {
                setEditMode(true);
                setTempEditText(selectionInfo.selectedText);
              }}
              className="flex items-center gap-1.5 px-3 py-1.5 text-[10px] font-mono uppercase font-bold text-cyan-400 hover:text-black hover:bg-cyan-500 rounded transition-all select-none"
            >
              <Edit3 size={11} />
              Correct_Selection
            </button>
          ) : (
            <div className="flex flex-col gap-1.5 w-64 select-none">
              <div className="flex justify-between items-center px-1">
                <span className="font-mono text-[8px] text-zinc-500 uppercase tracking-widest font-bold">Local Correction:</span>
                <span className="font-mono text-[8px] text-cyan-500 uppercase tracking-widest font-bold">Interactive</span>
              </div>
              <textarea
                value={tempEditText}
                onChange={(e) => setTempEditText(e.target.value)}
                className="w-full bg-zinc-900 border border-zinc-800 text-xs text-zinc-200 p-2 rounded font-sans focus:border-cyan-500 focus:outline-none min-h-[40px] resize-none"
                autoFocus
              />
              <div className="flex justify-end gap-1.5 px-1">
                <button
                  onClick={() => {
                    setSelectionInfo(null);
                    setEditMode(false);
                    setTempEditText('');
                  }}
                  className="px-2 py-1 text-[9px] font-mono text-zinc-550 hover:text-zinc-300 uppercase font-medium"
                >
                  Cancel
                </button>
                <button
                  onClick={handleSaveEdit}
                  className="px-3 py-1 text-[9px] font-mono bg-cyan-500 text-black font-bold rounded hover:bg-cyan-400 uppercase tracking-wider"
                >
                  Save
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Primary header panel */}
      <div className="h-12 bg-zinc-950 border-b border-zinc-800 flex items-center justify-between px-4 shrink-0 z-10">
        <div className="flex items-center gap-2">
          {onBack && (
            <button onClick={onBack} className="lg:hidden text-zinc-400 hover:text-white mr-2">
              <ArrowLeft size={16} />
            </button>
          )}
          <div className="w-2 h-2 rounded-full bg-cyan-500 animate-pulse"></div>
          <span className="text-[10px] font-mono text-zinc-400 uppercase tracking-widest">SESSION_DATA_STREAM</span>
        </div>
        <div className="flex items-center gap-3">
          
          {/* Feature 2: Sparkles Review Button */}
          <button
            onClick={handleSmartReview}
            disabled={isReviewing || !hasTimelineChanges}
            className="bg-purple-500/10 hover:bg-purple-500/20 text-purple-400 disabled:opacity-30 disabled:hover:bg-transparent border border-purple-500/35 px-3 py-1 rounded inline-flex items-center gap-1.5 transition-all text-[10px] font-mono font-bold uppercase tracking-wider h-7"
            title={hasTimelineChanges ? "Launch AI smart review propagation search" : "Unlock by making a manual edit on selections"}
          >
            {isReviewing ? (
              <>
                <Loader2 size={11} className="animate-spin" />
                Reviewing...
              </>
            ) : (
              <>
                <Sparkles size={11} />
                Smart_Review
              </>
            )}
          </button>

          <button
            onClick={handleManualFormalize}
            disabled={isFormalizing}
            className="text-orange-400 hover:text-orange-300 disabled:opacity-50 transition-colors shrink-0 p-1"
            title="Manual Re-run Formalization Engine"
          >
            {isFormalizing ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
          </button>
          
          {/* Formatted vs Raw Text Selector Switch */}
          {session && (session.formatted_text || session.raw_text) && (
            <div className="flex bg-zinc-900 border border-zinc-800 rounded p-0.5 pointer-events-auto shrink-0">
              <button
                onClick={() => setViewMode('formatted')}
                className={`px-2 py-0.5 font-mono text-[9px] uppercase font-bold rounded transition-all leading-none h-5 ${
                  viewMode === 'formatted' ? 'bg-cyan-500 text-black' : 'text-zinc-500 hover:text-white'
                }`}
                title="View formatted markdown layout"
              >
                Formatted
              </button>
              <button
                onClick={() => setViewMode('raw')}
                className={`px-2 py-0.5 font-mono text-[9px] uppercase font-bold rounded transition-all leading-none h-5 ${
                  viewMode === 'raw' ? 'bg-cyan-500 text-black' : 'text-zinc-500 hover:text-white'
                }`}
                title="View original raw transcript output"
              >
                Raw_Text
              </button>
            </div>
          )}

          {/* Feature 1: Markdown Copy Buttons */}
          <button 
            onClick={handleCopyMarkdown} 
            className="text-zinc-500 hover:text-cyan-400 transition-colors shrink-0 p-1 flex items-center justify-center" 
            title="Copy Markdown"
          >
            {copySuccess ? (
              <span className="text-[9px] font-mono text-green-500 flex items-center gap-1 select-none uppercase font-bold">
                <Check size={11} /> Copied
              </span>
            ) : (
              <Copy size={13} />
            )}
          </button>

          {/* Feature 1: PDF visual compilation */}
          <button 
            onClick={handleDownloadPDF} 
            className="text-zinc-500 hover:text-cyan-400 transition-colors shrink-0 p-1 flex items-center justify-center font-mono text-[10px]" 
            title="Download visual PDF"
          >
            <FileText size={13} className="text-orange-400 hover:text-orange-300" />
          </button>

          <button onClick={downloadTranscript} className="text-zinc-500 hover:text-cyan-400 transition-colors shrink-0 p-1" title="Download Markdown Filestream">
            <Download size={13} />
          </button>
        </div>
      </div>

      <div className="p-6 border-b border-zinc-800/55 bg-zinc-950/30 shrink-0">
        <h2 className="text-xl font-bold text-white mb-2">{session.name}</h2>
        <p className="text-xs text-zinc-400 mb-4">{session.description}</p>
        
        <div className="flex items-center justify-between">
          <div className="flex gap-2 flex-wrap">
            {session.tags.map(tag => (
              <span key={tag} className="text-[10px] bg-zinc-900 border border-zinc-800 text-zinc-400 px-2 py-1 rounded font-mono uppercase">
                <Tag size={10} className="inline mr-1" />{tag}
              </span>
            ))}
          </div>
          <span className="text-[10px] text-zinc-600 font-mono flex items-center gap-1 shrink-0">
            <Calendar size={12} />
            {new Date(session.createdAt).toLocaleDateString()}
          </span>
        </div>
      </div>

      {session.audioUrl && (
        <div className="p-4 border-b border-zinc-800/50 bg-zinc-950/50 flex items-center gap-4 shrink-0">
          <button 
            onClick={togglePlay}
            className="w-10 h-10 rounded-full bg-cyan-500/20 border border-cyan-500/50 text-cyan-400 flex items-center justify-center hover:bg-cyan-500 hover:text-black transition-all shrink-0"
          >
            {isPlaying ? <PauseCircle size={20} /> : <PlayCircle size={20} />}
          </button>
          <div className="flex-1 flex items-center min-w-0">
            <audio 
              ref={audioRef} 
              src={session.audioUrl} 
              onEnded={() => setIsPlaying(false)}
              controls 
              className="w-full h-8 max-w-md hidden"
            />
            <div className="h-1 w-full bg-zinc-800 rounded-full overflow-hidden relative">
              <div className="absolute inset-0 bg-gradient-to-r from-transparent via-cyan-500/50 to-cyan-500 w-full animate-pulse opacity-50" />
              <div className="absolute top-0 bottom-0 left-0 bg-cyan-500 scale-x-0 origin-left" id="play-progress" />
            </div>
            <p className="ml-4 font-mono text-[10px] text-zinc-500 uppercase tracking-widest shrink-0">SIGNAL_READY</p>
          </div>
          <a
            href={session.audioUrl}
            target="_blank"
            rel="noopener noreferrer"
            download
            className="text-zinc-500 hover:text-cyan-400 transition-colors shrink-0"
            title="Download Original Audio"
          >
            <HardDriveDownload size={16} />
          </a>
        </div>
      )}

      {/* Main Blocks Scroll Container */}
      <div className="flex-1 overflow-y-auto p-4 sm:p-6 custom-scrollbar text-xs sm:text-sm text-zinc-300 leading-relaxed max-w-none select-text">
        <div className="markdown-body font-sans space-y-4">
          
          {blocks.length === 0 ? (
            <p className="text-zinc-500 italic font-mono text-center py-8">EMPTY_STREAM_WAIT_STATE</p>
          ) : (
            blocks.map((block, index) => {
              const text = block.versions[block.activeIndex];
              const isEdited = block.versions.length > 1;

              return (
                <div 
                  key={index} 
                  className="group relative p-3 rounded-lg border border-transparent hover:border-zinc-800/30 hover:bg-zinc-900/10 transition-all duration-150 select-text"
                  onMouseUp={(e) => handleTextSelection(index, e)}
                >
                  <ReactMarkdown
                    remarkPlugins={[remarkGfm]}
                    components={{
                      table: ({node, ...props}) => <div className="overflow-x-auto my-2 border border-zinc-850 rounded-lg"><table className="min-w-full divide-y divide-zinc-800 text-left text-[11px] sm:text-xs" {...props} /></div>,
                      thead: ({node, ...props}) => <thead className="bg-[#0f0f12] text-zinc-400 font-mono text-[9px] sm:text-[10px] uppercase tracking-wider border-b border-zinc-800" {...props} />,
                      tbody: ({node, ...props}) => <tbody className="divide-y divide-zinc-800 bg-[#0c0c0e]/40" {...props} />,
                      tr: ({node, ...props}) => <tr className="hover:bg-zinc-900/30 transition-colors" {...props} />,
                      th: ({node, ...props}) => <th className="px-3 py-2 sm:px-4 sm:py-3 font-semibold text-cyan-400" {...props} />,
                      td: ({node, ...props}) => <td className="px-3 py-2 sm:px-4 sm:py-3 text-zinc-300 border-r border-zinc-900/50 last:border-r-0" {...props} />,
                      h1: ({node, ...props}) => <h1 className="text-base sm:text-lg font-bold text-white mt-1 mb-2 border-b border-zinc-805 pb-1 uppercase tracking-tight font-mono" {...props} />,
                      h2: ({node, ...props}) => <h2 className="text-sm sm:text-base font-bold text-cyan-500 mt-1 mb-2 font-mono" {...props} />,
                      h3: ({node, ...props}) => <h3 className="text-xs sm:text-sm font-bold text-white mb-2" {...props} />,
                      p: ({node, ...props}) => <p className="mb-0 text-[11px] sm:text-xs sm:text-sm leading-relaxed" {...props} />,
                      ul: ({node, ...props}) => <ul className="list-disc pl-5 mb-2 space-y-1 sm:space-y-1.5" {...props} />,
                      ol: ({node, ...props}) => <ol className="list-decimal pl-5 mb-2 space-y-1 sm:space-y-1.5" {...props} />,
                      li: ({node, ...props}) => <li className="pl-0.5 text-zinc-300 text-[11px] sm:text-xs" {...props} />,
                      blockquote: ({node, ...props}) => <blockquote className="border-l-2 border-cyan-500 pl-4 py-1.5 my-2 bg-cyan-950/10 text-zinc-300 italic rounded-r text-[11px] sm:text-xs" {...props} />,
                      code: ({node, ...props}) => <code className="bg-zinc-950 border border-zinc-800 px-1.5 py-0.5 rounded text-[10px] sm:text-[11px] font-mono text-cyan-400" {...props} />,
                    }}
                  >
                    {text}
                  </ReactMarkdown>

                  {/* Feature 3: Version navigation timeline next to edited blocks */}
                  {isEdited && (
                    <div className="flex items-center gap-2 mt-2 select-none font-mono text-[9px] uppercase tracking-wider text-zinc-500 bg-zinc-950/40 px-2 py-0.5 rounded inline-flex border border-zinc-800/30">
                      <button
                        onClick={() => handlePrevVersion(index)}
                        disabled={block.activeIndex === 0}
                        className="p-1 hover:text-cyan-400 disabled:opacity-30 disabled:hover:text-zinc-500 transition-colors"
                        title="View previous version"
                      >
                        <ChevronLeft size={10} />
                      </button>
                      <span className="text-zinc-400 font-bold">
                        {block.activeIndex + 1} / {block.versions.length}
                      </span>
                      <button
                        onClick={() => handleNextVersion(index)}
                        disabled={block.activeIndex === block.versions.length - 1}
                        className="p-1 hover:text-cyan-400 disabled:opacity-30 disabled:hover:text-zinc-500 transition-colors"
                        title="View next version"
                      >
                        <ChevronRight size={10} />
                      </button>
                      <span className="text-[8px] text-cyan-500/80 font-bold uppercase tracking-widest pl-1.5 border-l border-zinc-800 ml-1.5">
                        Timeline_Active
                      </span>
                    </div>
                  )}

                  {/* Contextual verification alert flag if semantic audit alters things */}
                  {block.flagged && (
                    <div className="mt-2.5 p-2 bg-amber-500/10 border border-amber-500/20 rounded flex items-start gap-2.5 text-[10px] text-amber-400 animate-in fade-in slide-in-from-top-1">
                      <AlertTriangle size={13} className="shrink-0 mt-0.5 text-amber-500 animate-pulse" />
                      <div className="flex-1">
                        <span className="font-bold uppercase tracking-wider font-mono mr-1">Semantic Shift Audit Required:</span>
                        <span>{block.warningReason}</span>
                      </div>
                    </div>
                  )}
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
};
