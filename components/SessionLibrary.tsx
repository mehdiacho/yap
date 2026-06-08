import React, { useState, useEffect } from 'react';
import { Archive, Search, Calendar, Tag, AudioLines, Download, PlayCircle, Loader2, Sparkles, RefreshCw, FolderPlus, CheckSquare, Square } from 'lucide-react';
import { TranscriptionSession, getAllSessions, updateSessionInFirestore, deleteSessionInFirestore, saveSessionToFirestore } from '../services/firebaseService';
import { processSessionMetadataAndFormatting, groupSessionsContextually } from '../services/geminiService';

export const SessionLibrary: React.FC<{
  onSelectSession: (session: TranscriptionSession) => void;
}> = ({ onSelectSession }) => {
  const [sessions, setSessions] = useState<TranscriptionSession[]>([]);
  const [loading, setLoading] = useState(true);
  const [isBulkProcessing, setIsBulkProcessing] = useState(false);
  const [isGrouping, setIsGrouping] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());

  useEffect(() => {
    loadSessions();
  }, []);

  const loadSessions = async () => {
    try {
      setLoading(true);
      const data = await getAllSessions();
      setSessions(data);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
      setSelectedIds(new Set());
    }
  };

  const handleBulkProcess = async () => {
    const unformalizedSessions = sessions.filter(s => !s.is_successful && s.raw_text);
    if (unformalizedSessions.length === 0) {
      alert("No legacy sessions found that require formalization.");
      return;
    }

    setIsBulkProcessing(true);
    let sessionList = [...sessions];

    for (let currentSess of unformalizedSessions) {
      try {
        const advancedMetadata = await processSessionMetadataAndFormatting(currentSess.raw_text!, sessions);
        const updates = {
          formatted_text: advancedMetadata.formatted_text,
          category: advancedMetadata.category,
          topicCluster: advancedMetadata.topicCluster,
          name: advancedMetadata.name,
          description: advancedMetadata.description,
          tags: advancedMetadata.tags,
          is_successful: true
        };
        await updateSessionInFirestore(currentSess.id!, updates);
        
        sessionList = sessionList.map(s => s.id === currentSess.id ? { ...s, ...updates } : s);
        setSessions(sessionList);

      } catch (err) {
        console.error("Bulk processing failed for session:", currentSess.id, err);
      }
    }
    
    setIsBulkProcessing(false);
    loadSessions();
  };

  const handleGroupContextually = async () => {
    if (selectedIds.size < 2) return;
    
    setIsGrouping(true);
    try {
      const selectedSessions = sessions.filter(s => s.id && selectedIds.has(s.id));
      const textsToGroup = selectedSessions.map(s => s.raw_text || s.transcript || '').filter(t => t.length > 0);
      
      if (textsToGroup.length === 0) {
        alert("No text available to group.");
        setIsGrouping(false);
        return;
      }

      const groupingOutput = await groupSessionsContextually(textsToGroup);
      
      const newSession: Omit<TranscriptionSession, 'id'> = {
        name: groupingOutput.name,
        description: groupingOutput.description,
        tags: groupingOutput.tags,
        category: groupingOutput.category,
        topicCluster: groupingOutput.topicCluster,
        transcript: textsToGroup.join('\n\n---\n\n'),
        raw_text: textsToGroup.join('\n\n---\n\n'),
        formatted_text: groupingOutput.formatted_text,
        createdAt: Date.now(),
        is_successful: true
      };

      await saveSessionToFirestore(newSession);

      // Delete the old ones that were grouped to keep archive clean
      for (let s of selectedSessions) {
        if (s.id) await deleteSessionInFirestore(s.id);
      }

      await loadSessions();
    } catch (err) {
      console.error("Error grouping contextually:", err);
      alert("Failed to group sessions.");
    } finally {
      setIsGrouping(false);
    }
  };

  const toggleSelection = (e: React.MouseEvent, id: string) => {
    e.stopPropagation();
    const newKeys = new Set(selectedIds);
    if (newKeys.has(id)) {
      newKeys.delete(id);
    } else {
      newKeys.add(id);
    }
    setSelectedIds(newKeys);
  };

  const filteredSessions = sessions.filter(session => {
    if (!searchQuery) return true;
    const query = searchQuery.toLowerCase();
    const matchName = session.name.toLowerCase().includes(query);
    const matchDesc = session.description.toLowerCase().includes(query);
    const matchTags = session.tags.some(tag => tag.toLowerCase().includes(query));
    const matchCategory = session.category && session.category.toLowerCase().includes(query);
    const matchCluster = session.topicCluster && session.topicCluster.toLowerCase().includes(query);
    const matchDate = new Date(session.createdAt).toLocaleDateString().includes(query);
    return matchName || matchDesc || matchTags || matchDate || matchCategory || matchCluster;
  });

  return (
    <div className="bg-zinc-900/40 border border-zinc-800 rounded-2xl p-6 flex flex-col h-full overflow-hidden">
      <div className="flex items-center justify-between mb-6">
        <h2 className="font-mono text-cyan-500 uppercase tracking-widest font-bold flex items-center gap-2">
          <Archive size={16} /> DATA_ARCHIVES
        </h2>
        <div className="flex items-center gap-3">
          {selectedIds.size > 1 && (
            <button 
              onClick={handleGroupContextually}
              disabled={isGrouping}
              className="flex items-center gap-1.5 text-cyan-400 hover:text-cyan-300 disabled:opacity-50 transition-colors uppercase font-mono text-[9px] tracking-widest border border-cyan-500/20 bg-cyan-500/10 px-2 py-1 rounded"
            >
              {isGrouping ? <Loader2 size={12} className="animate-spin" /> : <FolderPlus size={12} />}
              {isGrouping ? 'GROUPING...' : 'GROUP CONTEXTUALLY'}
            </button>
          )}

          <button 
            onClick={handleBulkProcess}
            disabled={isBulkProcessing}
            className="flex items-center gap-1.5 text-orange-400 hover:text-orange-300 disabled:opacity-50 transition-colors uppercase font-mono text-[9px] tracking-widest border border-orange-500/20 bg-orange-500/10 px-2 py-1 rounded"
          >
            {isBulkProcessing ? <Loader2 size={12} className="animate-spin" /> : <Sparkles size={12} />}
            {isBulkProcessing ? 'PROCESSING...' : 'BULK FORMALIZE'}
          </button>
          <button 
            onClick={loadSessions}
            className="text-zinc-500 hover:text-cyan-400 transition-colors uppercase font-mono text-[9px] tracking-widest"
          >
            REFRESH
          </button>
        </div>
      </div>

      <div className="relative mb-6">
        <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
          <Search size={14} className="text-zinc-500" />
        </div>
        <input
          type="text"
          className="bg-zinc-950 border border-zinc-800 text-zinc-300 text-sm rounded-lg focus:outline-none focus:border-cyan-500 block w-full pl-9 p-2.5 font-mono placeholder-zinc-700"
          placeholder="SEARCH_INDEX, CATEGORY, OR PROJECT..."
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
        />
      </div>

      <div className="flex-1 overflow-y-auto custom-scrollbar pr-2 space-y-4">
        {loading ? (
          <div className="flex justify-center py-10">
            <Loader2 className="animate-spin text-cyan-500" />
          </div>
        ) : filteredSessions.length === 0 ? (
          <div className="text-center py-10 text-zinc-600 font-mono text-xs uppercase">
            NO_RECORDS_FOUND
          </div>
        ) : (
          filteredSessions.map(session => (
            <div 
              key={session.id} 
              onClick={() => onSelectSession(session)}
              className={`bg-zinc-950 border ${selectedIds.has(session.id!) ? 'border-cyan-500/80 bg-cyan-950/20' : 'border-zinc-800/50 hover:border-cyan-500/50'} rounded-xl p-4 cursor-pointer transition-all group relative overflow-hidden`}
            >
              <div className="flex justify-between items-start mb-1.5">
                <div className="flex items-center gap-3 pr-2 overflow-hidden w-full">
                  <div 
                    className="shrink-0 text-zinc-500 hover:text-cyan-400 z-10"
                    onClick={(e) => toggleSelection(e, session.id!)}
                  >
                    {selectedIds.has(session.id!) ? (
                      <CheckSquare size={16} className="text-cyan-500" />
                    ) : (
                      <Square size={16} />
                    )}
                  </div>
                  <h3 className="font-bold text-zinc-200 group-hover:text-cyan-400 transition-colors truncate">{session.name}</h3>
                </div>
                <span className="text-[10px] text-zinc-600 font-mono flex items-center gap-1 shrink-0 ml-2">
                  <Calendar size={10} />
                  {new Date(session.createdAt).toLocaleDateString()}
                </span>
              </div>

              {/* Category Pill and Sub-info */}
              <div className="flex items-center gap-2 mb-2 flex-wrap pl-7">
                {session.category && (
                  <span className="text-[9px] bg-cyan-950/40 text-cyan-400 border border-cyan-800/50 px-2 py-0.5 rounded-full font-mono uppercase tracking-widest font-bold">
                    {session.category}
                  </span>
                )}
                {session.topicCluster && session.topicCluster !== 'General' && (
                  <span className="text-[9px] bg-zinc-900 text-zinc-300 border border-zinc-800 px-2 py-0.5 rounded font-mono uppercase font-medium">
                    📁 Cluster: {session.topicCluster}
                  </span>
                )}
              </div>

              <p className="text-xs text-zinc-500 mb-3 line-clamp-2 pl-7">
                {session.description}
              </p>
              <div className="flex items-center justify-between pl-7">
                <div className="flex items-center gap-2 flex-wrap">
                  {session.tags.map(tag => (
                    <span key={tag} className="text-[9px] bg-zinc-900 border border-zinc-800 text-zinc-400 px-2 py-0.5 rounded font-mono uppercase tracking-tight">
                      <Tag size={8} className="inline mr-1" />{tag}
                    </span>
                  ))}
                </div>
                {session.audioUrl && (
                   <AudioLines size={14} className="text-zinc-600 group-hover:text-cyan-500 transition-colors" />
                )}
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
};

