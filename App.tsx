
import React, { useState, useRef, useEffect } from 'react';
import { Upload, FileAudio, Zap, PlayCircle, Loader2, X, Download, Copy, CheckCircle2, FileText, Settings, Mic, Square } from 'lucide-react';
import WaveformPreview from './components/WaveformPreview';
import TerminalOutput from './components/TerminalOutput';
import { AppStatus, SystemLog, ProcessingSegment } from './types';
import { sliceAudioBuffer, sliceAndDownsampleAudio } from './services/wavEncoder';
import { transcribeAudio, generateSessionMetadata, stitchWithBridge, processSessionMetadataAndFormatting } from './services/geminiService';
import { uploadAudioToStorage, saveSessionToFirestore, getAllSessions } from './services/firebaseService';

import { SessionLibrary } from './components/SessionLibrary';
import { SessionViewer } from './components/SessionViewer';
export interface UploadQueueItem {
  id: string;
  files: File[];
  selected: boolean;
  status: AppStatus;
}

const SEGMENT_THRESHOLD = 270; // 4 minutes 30 seconds
const BUFFER_MIN_DURATION = 5; // 5 seconds buffer rule

const App: React.FC = () => {
  const [status, setStatus] = useState<AppStatus>(AppStatus.IDLE);
  const [queue, setQueue] = useState<UploadQueueItem[]>([]);
  const [logs, setLogs] = useState<SystemLog[]>([]);
  const [transcript, setTranscript] = useState<string>('');
  const [copyFeedback, setCopyFeedback] = useState(false);
  const [isVttMode, setIsVttMode] = useState(false);
  
  const fileInputRef = useRef<HTMLInputElement>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const audioChunksRef = useRef<BlobPart[]>([]);
  const [recordingTime, setRecordingTime] = useState<number>(0);
  const recordingTimerRef = useRef<number | null>(null);

  const [viewMode, setViewMode] = useState<'generator' | 'library'>('generator');
  const [selectedSession, setSelectedSession] = useState<TranscriptionSession | null>(null);

  const addLog = (message: string, type: SystemLog['type'] = 'INFO') => {
    const newLog: SystemLog = {
      id: crypto.randomUUID(),
      timestamp: new Date().toLocaleTimeString([], { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' }),
      message,
      type
    };
    setLogs(prev => [...prev, newLog]);
  };

  /**
   * Finalization Step: Global WebVTT Re-indexing
   * Prepends 'WEBVTT' header and re-labels every cue index sequentially.
   * Ensures timestamp milliseconds use periods.
   */
  const reindexVTT = (rawString: string): string => {
    const lines = rawString.replace(/\r\n/g, '\n').split('\n');
    let counter = 1;
    const resultLines: string[] = ['WEBVTT', ''];
    
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i].trim();
      if (!line) continue; // Skip empty lines to normalize cue blocks

      const nextLine = lines[i + 1]?.trim() || '';
      
      // Check for timestamp line pattern: HH:MM:SS.mmm --> HH:MM:SS.mmm
      // We also handle cases where AI might still use commas to be safe and convert them.
      const timestampPattern = /\d{2}:\d{2}:\d{2}[\.,]\d{3} --> \d{2}:\d{2}:\d{2}[\.,]\d{3}/;
      const isTimestampLine = timestampPattern.test(line);
      const isNextTimestamp = timestampPattern.test(nextLine);
      const isIndexCandidate = /^\d+$/.test(line);

      if (isIndexCandidate && isNextTimestamp) {
        // This is a cue index line
        resultLines.push((counter++).toString());
      } else if (isTimestampLine) {
        // Ensure milliseconds use period
        const correctedLine = line.replace(/,/g, '.');
        resultLines.push(correctedLine);
      } else {
        resultLines.push(line);
      }
      
      // Add spacing between blocks for readability if next block starts or current ends
      if (i < lines.length - 1 && lines[i+1].trim() === '') {
        resultLines.push('');
      }
    }
    
    return resultLines.join('\n');
  };

  const initAudioContext = () => {
    if (!audioContextRef.current) {
      audioContextRef.current = new (window.AudioContext || (window as any).webkitAudioContext)();
    }
    return audioContextRef.current;
  };

  const handleFileSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const selectedFiles = e.target.files;
    if (!selectedFiles || selectedFiles.length === 0) return;

    const newItems: UploadQueueItem[] = Array.from(selectedFiles).map((f) => ({
      id: crypto.randomUUID(),
      files: [f],
      selected: false,
      status: AppStatus.IDLE
    }));

    setQueue(prev => [...prev, ...newItems]);
    addLog(`QUEUED ${newItems.length} FILES FOR PROCESSING`, 'SUCCESS');
    if (status === AppStatus.IDLE || status === AppStatus.COMPLETED || status === AppStatus.ERROR) {
      setStatus(AppStatus.READY);
    }
    
    if (fileInputRef.current) {
        fileInputRef.current.value = '';
    }
  };

  const toggleQueueItem = (id: string) => {
    setQueue(prev => prev.map(q => q.id === id ? { ...q, selected: !q.selected } : q));
  };

  const joinSelectedQueueItems = () => {
    setQueue(prev => {
      const selected = prev.filter(q => q.selected);
      if (selected.length < 2) return prev;
      
      const unselected = prev.filter(q => !q.selected);
      const combinedFiles = selected.flatMap(q => q.files);
      
      const newItem: UploadQueueItem = {
        id: crypto.randomUUID(),
        files: combinedFiles,
        selected: false,
        status: AppStatus.IDLE
      };
      
      return [...unselected, newItem];
    });
    addLog('JOINED SELECTED FILES INTO SINGLE DOCUMENT', 'INFO');
  };

  const clearQueue = () => {
    setQueue([]);
    setTranscript('');
    setStatus(AppStatus.IDLE);
    addLog('QUEUE_CLEARED', 'INFO');
  };

  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const mediaRecorder = new MediaRecorder(stream);
      mediaRecorderRef.current = mediaRecorder;
      audioChunksRef.current = [];

      mediaRecorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          audioChunksRef.current.push(event.data);
        }
      };

      mediaRecorder.onstop = async () => {
        const audioBlob = new Blob(audioChunksRef.current, { type: 'audio/webm' });
        const recordedFile = new File([audioBlob], `recording-${new Date().toISOString().replace(/:/g, '-')}.webm`, { type: 'audio/webm' });
        
        setQueue(prev => [
            ...prev,
            { id: crypto.randomUUID(), files: [recordedFile], selected: false, status: AppStatus.IDLE }
        ]);
        
        setStatus(AppStatus.READY);
        addLog(`RECORDING_QUEUED: ${recordedFile.name}`, 'SUCCESS');
        stream.getTracks().forEach(track => track.stop());
      };

      mediaRecorder.start(100);
      setStatus(AppStatus.RECORDING);
      addLog(`MIC_HOT_PROTOCOL_ENGAGED: RECORDING_STARTED`, 'SUCCESS');
      
      setRecordingTime(0);
      recordingTimerRef.current = window.setInterval(() => {
        setRecordingTime(prev => prev + 1);
      }, 1000);

    } catch (err) {
      addLog(`CRITICAL_ERROR: MIC_ACCESS_DENIED_OR_UNAVAILABLE`, 'ERROR');
      setStatus(AppStatus.ERROR);
    }
  };

  const stopRecording = () => {
    if (mediaRecorderRef.current && mediaRecorderRef.current.state === 'recording') {
      mediaRecorderRef.current.stop();
      if (recordingTimerRef.current) {
        window.clearInterval(recordingTimerRef.current);
      }
      addLog(`MIC_HOT_PROTOCOL_DISENGAGED: RECORDING_STOPPED`, 'INFO');
    }
  };

  const resetApp = () => {
    setFile(null);
    setAudioBuffer(null);
    setLogs([]);
    setTranscript('');
    setStatus(AppStatus.IDLE);
    setCopyFeedback(false);
    if (fileInputRef.current) fileInputRef.current.value = '';
    if (mediaRecorderRef.current && mediaRecorderRef.current.state === 'recording') {
      mediaRecorderRef.current.stop();
    }
    if (recordingTimerRef.current) {
      window.clearInterval(recordingTimerRef.current);
      recordingTimerRef.current = null;
    }
    setRecordingTime(0);
  };

  const startProcessing = async () => {
    if (queue.length === 0) return;

    setStatus(AppStatus.PROCESSING);
    setTranscript('');
    addLog(`INITIATING_LINEAR_OPTIMIZATION_PROTOCOL_QUEUE`, 'PROCESS');
    addLog(`ENCODER_TARGET: ${isVttMode ? 'WEBVTT_VTT_FORMAT' : 'STANDARD_PLAINTEXT'}`, 'INFO');

    const context = initAudioContext();

    try {
      for (let qIndex = 0; qIndex < queue.length; qIndex++) {
        const item = queue[qIndex];
        
        // Mark as processing
        setQueue(prev => prev.map(q => q.id === item.id ? { ...q, status: AppStatus.PROCESSING } : q));
        addLog(`PROCESSING_QUEUE_ITEM [${qIndex + 1}/${queue.length}] (${item.files.length} FILES)`, 'PROCESS');

        let itemTranscriptAcc = [];

        // Inner loop: if item has multiple grouped files, concatenate their transcripts
        for (let fIndex = 0; fIndex < item.files.length; fIndex++) {
           const f = item.files[fIndex];
           addLog(`DECODING_FILE: ${f.name}`, 'PROCESS');
           
           try {
             const arrayBuffer = await f.arrayBuffer();
             const decodedBuffer = await context.decodeAudioData(arrayBuffer);
             
             const duration = decodedBuffer.duration;
             const segments: { start: number; end: number }[] = [];
             
             let current = 0;
             while (current < duration) {
               let end = current + SEGMENT_THRESHOLD;
               let remaining = duration - end;
               
               if (remaining < BUFFER_MIN_DURATION) {
                 end = duration;
               }
               
               segments.push({ start: current, end: end });
               current = end;
             }

             let finalTranscript = '';

             if (segments.length === 1) {
                const seg = segments[0];
                addLog(`SINGLE_UNIT_DIRECT [START: 0.0s]`, 'PROCESS');
                const blob = await sliceAndDownsampleAudio(decodedBuffer, seg.start, seg.end);
                finalTranscript = (await transcribeAudio(blob, isVttMode, 0)).trim();
             } else {
                const seg0 = segments[0];
                const blob0 = await sliceAndDownsampleAudio(decodedBuffer, seg0.start, seg0.end);
                let accumulatedText = (await transcribeAudio(blob0, isVttMode, 0)).trim();
                
                for (let i = 1; i < segments.length; i++) {
                  const seg = segments[i];
                  const boundaryTime = seg.start;
                  
                  const bridgeStart = Math.max(0, boundaryTime - 30);
                  const bridgeEnd = Math.min(decodedBuffer.duration, boundaryTime + 30);
                  
                  const bridgeBlob = await sliceAndDownsampleAudio(decodedBuffer, bridgeStart, bridgeEnd);
                  const bridgeText = await transcribeAudio(bridgeBlob, isVttMode, bridgeStart);
         
                  const segBlob = await sliceAndDownsampleAudio(decodedBuffer, seg.start, seg.end);
                  const segText = await transcribeAudio(segBlob, isVttMode, boundaryTime);
        
                  accumulatedText = await stitchWithBridge(accumulatedText, segText, bridgeText);
                }
                finalTranscript = accumulatedText;
             }

             if (isVttMode) {
                finalTranscript = reindexVTT(finalTranscript);
             }
             itemTranscriptAcc.push(finalTranscript);
             setTranscript(prev => prev + (prev ? '\n\n---\n\n' : '') + finalTranscript);

           } catch(err) {
             addLog(`FAILED_TO_DECODE_OR_PROCESS: ${f.name}`, 'ERROR');
           }
        }

        const consolidatedText = itemTranscriptAcc.join('\n\n---\n\n');
        
        try {
          const recentSessions = await getAllSessions();
          let audioUrl = '';
          try {
              // Upload only the first file to cloud due to generic audioUrl field
              if (item.files.length > 0) {
                 audioUrl = await uploadAudioToStorage(item.files[0], Date.now());
                 addLog('AUDIO_UPLOADED_TO_CLOUD', 'SUCCESS');
              }
          } catch(err) {
              console.error(err);
          }

          const advancedMetadata = await processSessionMetadataAndFormatting(consolidatedText, recentSessions);
          
          await saveSessionToFirestore({
            transcript: consolidatedText,
            raw_text: consolidatedText,
            formatted_text: advancedMetadata.formatted_text,
            category: advancedMetadata.category,
            topicCluster: advancedMetadata.topicCluster,
            name: advancedMetadata.name,
            description: advancedMetadata.description,
            tags: advancedMetadata.tags,
            audioUrl,
            createdAt: Date.now(),
            is_successful: true
          });
          
          setQueue(prev => prev.map(q => q.id === item.id ? { ...q, status: AppStatus.COMPLETED } : q));
          addLog('ITEM_ARCHIVED_IN_DATABANK', 'SUCCESS');
        } catch(err) {
          setQueue(prev => prev.map(q => q.id === item.id ? { ...q, status: AppStatus.ERROR } : q));
          addLog('ITEM_ARCHIVE_FAILED', 'ERROR');
        }
      }

      setStatus(AppStatus.COMPLETED);
      addLog(`PROTOCOL_TERMINATED: ALL_DATA_SEQUENCED`, 'SUCCESS');
    } catch (error: any) {
      const msg = error.message === 'API_KEY_NOT_FOUND' ? 'API_KEY_MISSING' : 'NEURAL_LINK_ERROR';
      addLog(`CRITICAL_ERROR: ${msg}`, 'ERROR');
      setStatus(AppStatus.ERROR);
    }
  };

  const downloadTranscript = () => {
    const extension = isVttMode ? 'vtt' : 'txt';
    const mimeType = isVttMode ? 'text/vtt' : 'text/plain';
    const blob = new Blob([transcript], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${isVttMode ? 'SUBTITLES' : 'TRANSCRIPT'}_${queue.length > 0 ? queue[0].files[0].name.split('.')[0] : 'OUTPUT'}.${extension}`;
    a.click();
    URL.revokeObjectURL(url);
    addLog(`DATA_EXPORTED: .${extension.toUpperCase()}_FILE_READY`, "SUCCESS");
  };

  const copyToClipboard = async () => {
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
    addLog("TRANSCRIPT_COPIED_TO_LOCAL_BUFFER", "SUCCESS");
    setTimeout(() => setCopyFeedback(false), 2000);
  };

  return (
    <div className="h-[100dvh] bg-[#09090b] text-zinc-300 font-sans selection:bg-cyan-500 selection:text-black flex flex-col overflow-hidden">
      <header className="border-b border-zinc-800 bg-zinc-950/80 backdrop-blur shrink-0 z-50">
        <div className="max-w-7xl mx-auto px-4 lg:px-8 py-4 lg:py-0 lg:h-20 flex flex-col lg:flex-row lg:items-center justify-between gap-4">
          <div className="flex items-center justify-between lg:justify-start gap-4">
            <div className="flex items-center gap-4">
              <div className="w-3 lg:w-4 h-10 lg:h-14 bg-cyan-500 shadow-[0_0_20px_rgba(6,182,212,0.6)] rounded-sm"></div>
              <div>
                <h1 className="text-xl lg:text-2xl font-mono font-bold tracking-[0.2em] lg:tracking-[0.3em] text-white flex items-center gap-2 lg:gap-3">
                  TERMINAL-U <span className="text-zinc-700">//</span> TRANSCRIBER
                </h1>
                <p className="font-mono text-[7px] lg:text-[9px] text-zinc-500 tracking-[0.3em] lg:tracking-[0.5em] uppercase mt-1">
                  Linear_Audio_Optimization_V2.0
                </p>
              </div>
            </div>
            
            {/* System state on mobile floats right of title */}
            <div className="flex flex-col items-end lg:hidden">
              <span className="font-mono text-[8px] text-zinc-500 uppercase">State</span>
              <span className={`font-mono text-[10px] font-bold uppercase ${
                status === AppStatus.PROCESSING ? 'text-cyan-400 animate-pulse' :
                status === AppStatus.RECORDING ? 'text-red-500 animate-pulse' :
                status === AppStatus.COMPLETED ? 'text-green-500' :
                status === AppStatus.ERROR ? 'text-red-500' : 'text-zinc-400'
              }`}>
                {status}
              </span>
            </div>
          </div>
          
          <div className="flex flex-col sm:flex-row items-center gap-4 lg:gap-6 w-full lg:w-auto">
            <div className="flex bg-zinc-900 border border-zinc-800 rounded-lg overflow-hidden w-full sm:w-auto">
              <button 
                onClick={() => { setViewMode('generator'); setSelectedSession(null); }} 
                className={`flex-1 sm:flex-none px-4 py-2 font-mono text-[10px] uppercase font-bold transition-all ${viewMode === 'generator' ? 'bg-cyan-500 text-black' : 'text-zinc-500 hover:text-white'}`}
              >
                Generator
              </button>
              <button 
                onClick={() => setViewMode('library')} 
                className={`flex-1 sm:flex-none px-4 py-2 font-mono text-[10px] uppercase font-bold transition-all ${viewMode === 'library' ? 'bg-cyan-500 text-black' : 'text-zinc-500 hover:text-white'}`}
              >
                Archives
              </button>
            </div>
            
            <div className="hidden lg:flex flex-col items-end">
              <span className="font-mono text-[10px] text-zinc-500 uppercase">System_State</span>
              <span className={`font-mono text-xs font-bold uppercase ${
                status === AppStatus.PROCESSING ? 'text-cyan-400 animate-pulse' :
                status === AppStatus.RECORDING ? 'text-red-500 animate-pulse' :
                status === AppStatus.COMPLETED ? 'text-green-500' :
                status === AppStatus.ERROR ? 'text-red-500' : 'text-zinc-400'
              }`}>
                {status}
              </span>
            </div>
          </div>
        </div>
      </header>

      <main className="flex-1 max-w-7xl mx-auto w-full p-4 lg:p-8 flex flex-col lg:grid lg:grid-cols-5 gap-6 lg:gap-8 overflow-y-auto custom-scrollbar">
        {viewMode === 'generator' ? (
          <>
            <div className="lg:col-span-2 flex flex-col gap-6 shrink-0">
              <div className="bg-zinc-900/40 border border-zinc-800 rounded-2xl p-6 lg:p-8 flex flex-col gap-6 lg:gap-8 shadow-xl relative overflow-hidden">
                <div className="absolute top-0 right-0 p-4 opacity-5 pointer-events-none">
                  <FileAudio size={80} className="lg:w-[120px] lg:h-[120px]" />
                </div>

                <div className="flex flex-col gap-2">
                  <h2 className="font-mono text-[10px] text-cyan-500 uppercase tracking-widest font-bold flex items-center gap-2">
                    <Settings size={12} />
                    01. Ingestion_Protocol
                  </h2>
                  <p className="text-zinc-500 text-xs">Load audio source and select encoder target.</p>
                </div>

                <div className="flex flex-col gap-4 bg-zinc-950/50 p-4 rounded-xl border border-zinc-800/50">
                  <div className="flex items-center justify-between">
                    <div className="flex flex-col">
                      <span className="font-mono text-[10px] text-white font-bold uppercase tracking-wider">YT_VTT_ENCODER</span>
                      <span className="text-[9px] text-zinc-600 uppercase">Web Media Text Tracks</span>
                    </div>
                    <button 
                      onClick={() => setIsVttMode(!isVttMode)}
                      disabled={status === AppStatus.PROCESSING}
                      className={`w-12 h-6 rounded-full transition-all relative ${isVttMode ? 'bg-cyan-500 shadow-[0_0_15px_rgba(6,182,212,0.4)]' : 'bg-zinc-800'} ${status === AppStatus.PROCESSING ? 'opacity-50 cursor-not-allowed' : ''}`}
                    >
                      <div className={`absolute top-1 w-4 h-4 rounded-full transition-all ${isVttMode ? 'left-7 bg-black' : 'left-1 bg-zinc-500'}`}></div>
                    </button>
                  </div>
                </div>

                {queue.length === 0 ? (
                  status === AppStatus.RECORDING ? (
                    <div className="h-32 border-2 border-red-500/50 rounded-xl flex flex-col items-center justify-center gap-4 transition-all bg-red-950/20">
                      <div className="flex items-center gap-3">
                        <div className="w-3 h-3 rounded-full bg-red-500 animate-pulse" />
                        <span className="font-mono text-xs font-bold text-red-500 uppercase tracking-widest">
                          Recording ({Math.floor(recordingTime / 60).toString().padStart(2, '0')}:{(recordingTime % 60).toString().padStart(2, '0')})
                        </span>
                      </div>
                      <button 
                        onClick={stopRecording}
                        className="flex items-center gap-2 px-4 py-2 bg-red-500/10 hover:bg-red-500/20 text-red-400 rounded-lg border border-red-500/50 font-mono text-[10px] font-bold uppercase transition-colors"
                      >
                        <Square size={14} /> Stop_Recording
                      </button>
                    </div>
                  ) : (
                    <div className="grid grid-cols-2 gap-4">
                      <button 
                        onClick={() => fileInputRef.current?.click()}
                        className="group relative h-32 border-2 border-dashed border-zinc-800 hover:border-cyan-500/50 rounded-xl flex flex-col items-center justify-center gap-4 transition-all bg-zinc-950/50"
                      >
                        <div className="w-10 h-10 bg-zinc-900 rounded-full flex items-center justify-center group-hover:scale-110 group-hover:bg-cyan-500/10 transition-all">
                          <Upload size={18} className="text-zinc-500 group-hover:text-cyan-400" />
                        </div>
                        <div className="flex flex-col items-center text-center px-2">
                          <span className="font-mono text-[10px] font-bold text-zinc-400 group-hover:text-white uppercase tracking-widest">Upload_File</span>
                        </div>
                        <input 
                          type="file" 
                          ref={fileInputRef} 
                          onChange={handleFileSelect} 
                          className="hidden" 
                          accept="audio/*"
                          multiple
                        />
                      </button>
                      
                      <button 
                        onClick={startRecording}
                        className="group relative h-32 border-2 border-dashed border-zinc-800 hover:border-red-500/50 rounded-xl flex flex-col items-center justify-center gap-4 transition-all bg-zinc-950/50"
                      >
                        <div className="w-10 h-10 bg-zinc-900 rounded-full flex items-center justify-center group-hover:scale-110 group-hover:bg-red-500/10 transition-all">
                      <Mic size={18} className="text-zinc-500 group-hover:text-red-400" />
                    </div>
                    <div className="flex flex-col items-center text-center px-2">
                      <span className="font-mono text-[10px] font-bold text-zinc-400 group-hover:text-white uppercase tracking-widest">Record_Mic</span>
                    </div>
                  </button>
                </div>
              )
            ) : (
              <div className="flex flex-col gap-2 max-h-48 overflow-y-auto custom-scrollbar">
                {queue.map((item, idx) => (
                  <div key={item.id} className="bg-zinc-950 border border-zinc-800 rounded-xl p-3 flex items-center justify-between group">
                    <div className="flex items-center gap-3 overflow-hidden w-full">
                      {status !== AppStatus.PROCESSING && status !== AppStatus.COMPLETED && (
                        <button onClick={() => toggleQueueItem(item.id)} className="text-zinc-500 hover:text-cyan-400">
                          {item.selected ? <CheckCircle2 size={16} className="text-cyan-500" /> : <div className="w-4 h-4 border border-zinc-600 rounded-sm" />}
                        </button>
                      )}
                      <div className="w-8 h-8 bg-cyan-500/10 rounded flex items-center justify-center shrink-0 text-cyan-500">
                        <FileAudio size={16} />
                      </div>
                      <div className="flex flex-col overflow-hidden">
                        <span className="text-white font-mono text-[10px] truncate font-bold uppercase tracking-tight">
                          {item.files.map(f => f.name).join(' + ')}
                        </span>
                        <span className="text-zinc-500 font-mono text-[8px] uppercase tracking-widest">
                          Items: {item.files.length} | Status: {item.status}
                        </span>
                      </div>
                    </div>
                  </div>
                ))}
                
                {status !== AppStatus.PROCESSING && status !== AppStatus.COMPLETED && (
                  <div className="flex items-center gap-2 mt-2">
                    {queue.filter(q => q.selected).length > 1 && (
                      <button 
                        onClick={joinSelectedQueueItems}
                        className="flex-1 py-2 rounded-lg bg-zinc-800 text-cyan-400 font-mono text-[9px] font-bold uppercase tracking-widest border border-cyan-500/20 hover:bg-zinc-700"
                      >
                        Join Selected
                      </button>
                    )}
                    <button 
                      onClick={clearQueue}
                      className="flex-1 py-2 rounded-lg bg-red-500/10 text-red-500 font-mono text-[9px] font-bold uppercase tracking-widest border border-red-500/20 hover:bg-red-500/20"
                    >
                      Clear Queue
                    </button>
                  </div>
                )}
              </div>
            )}

            <div className="mt-auto space-y-4">
              {status === AppStatus.COMPLETED ? (
                <div className="animate-in fade-in slide-in-from-bottom-2 duration-500 space-y-3">
                  <div className="flex items-center gap-2 mb-2 p-3 bg-green-500/10 border border-green-500/20 rounded-lg">
                    <CheckCircle2 size={16} className="text-green-500" />
                    <span className="font-mono text-[10px] font-bold text-green-500 uppercase tracking-widest">Transcription_Phase_Complete</span>
                  </div>
                  
                  <button
                    onClick={downloadTranscript}
                    className={`w-full py-4 rounded-xl font-mono text-xs font-bold uppercase tracking-[0.3em] flex items-center justify-center gap-3 transition-all active:scale-[0.98] ${isVttMode ? 'bg-cyan-500 text-black shadow-[0_0_25px_rgba(6,182,212,0.4)] hover:bg-cyan-400' : 'bg-white text-black hover:bg-zinc-200'}`}
                  >
                    <Download size={18} />
                    {isVttMode ? 'Export_VTT_File' : 'Download_Transcript'}
                  </button>
                  
                  <button
                    onClick={copyToClipboard}
                    className="w-full py-3 rounded-xl bg-zinc-800 text-cyan-400 font-mono text-[10px] font-bold uppercase tracking-[0.3em] flex items-center justify-center gap-3 transition-all active:scale-[0.98] border border-zinc-700 hover:bg-zinc-700"
                  >
                    {copyFeedback ? (
                      <>
                        <CheckCircle2 size={16} />
                        Buffer_Copied
                      </>
                    ) : (
                      <>
                        <Copy size={16} />
                        Copy_Buffer
                      </>
                    )}
                  </button>

                  <button 
                    onClick={resetApp}
                    className="w-full py-2 font-mono text-[9px] text-zinc-600 hover:text-zinc-400 transition-colors uppercase tracking-[0.2em]"
                  >
                    Clear_Array_And_Restart
                  </button>
                </div>
              ) : (
                <button
                  onClick={startProcessing}
                  disabled={queue.length === 0 || status === AppStatus.PROCESSING || status === AppStatus.LOADING}
                  className={`w-full py-4 rounded-xl font-mono text-xs font-bold uppercase tracking-[0.3em] flex items-center justify-center gap-3 transition-all active:scale-[0.98] 
                    ${status === AppStatus.PROCESSING 
                      ? 'bg-zinc-800 text-zinc-500 cursor-not-allowed' 
                      : 'bg-cyan-500 text-black shadow-[0_0_25px_rgba(6,182,212,0.4)] hover:shadow-[0_0_35px_rgba(6,182,212,0.6)] hover:bg-cyan-400'
                    }
                    disabled:opacity-40 disabled:shadow-none
                  `}
                >
                  {status === AppStatus.PROCESSING ? (
                    <>
                      <Loader2 size={16} className="animate-spin" />
                      Processing_Neural_Array
                    </>
                  ) : (
                    <>
                      <PlayCircle size={18} fill="currentColor" />
                      Start_Linear_Optim
                    </>
                  )}
                </button>
              )}
              
              {status !== AppStatus.COMPLETED && (
                <div className="flex items-center gap-3 mt-4 p-4 bg-zinc-950/80 border border-zinc-800/50 rounded-lg">
                  <Zap size={14} className="text-cyan-500" />
                  <div className="flex flex-col">
                    <span className="font-mono text-[9px] font-bold text-zinc-400 uppercase tracking-widest">Logic: {isVttMode ? 'Continuous_Time_VTT' : 'Standard_Linear'}</span>
                    <span className="font-mono text-[8px] text-zinc-600 uppercase">Interval: 270s | Buffer: 5s</span>
                  </div>
                </div>
              )}
            </div>
          </div>
          
          <WaveformPreview audioBlob={queue.length > 0 && queue[0].files.length > 0 ? queue[0].files[0] : null} />
        </div>

        <div className="lg:col-span-3 min-h-[500px] lg:h-[calc(100dvh-140px)] shrink-0 flex flex-col">
          <TerminalOutput 
            logs={logs} 
            transcript={transcript} 
            isProcessing={status === AppStatus.PROCESSING} 
            onDownload={downloadTranscript}
          />
        </div>
        </>
        ) : (
          <>
            <div className={`lg:col-span-2 min-h-[400px] lg:h-[calc(100dvh-140px)] shrink-0 ${selectedSession ? 'hidden lg:block' : 'block'}`}>
              <SessionLibrary onSelectSession={setSelectedSession} />
            </div>
            <div className={`lg:col-span-3 h-[calc(100dvh-140px)] lg:h-[calc(100dvh-140px)] shrink-0 ${!selectedSession ? 'hidden lg:block' : 'block'}`}>
              <SessionViewer session={selectedSession} onBack={() => setSelectedSession(null)} />
            </div>
          </>
        )}
      </main>

      <footer className="h-10 bg-zinc-950 border-t border-zinc-800 px-8 flex items-center justify-between font-mono text-[9px] text-zinc-600 uppercase tracking-widest">
        <div className="flex items-center gap-6">
          <span className="flex items-center gap-2">
            <div className={`w-1.5 h-1.5 rounded-full ${status === AppStatus.ERROR ? 'bg-red-500' : 'bg-green-500 animate-pulse'}`}></div>
            Neural_Link_Status: {status === AppStatus.ERROR ? 'Fault' : 'Online'}
          </span>
          <span className="flex items-center gap-2">
            <div className={`w-1.5 h-1.5 rounded-full ${isVttMode ? 'bg-cyan-500' : 'bg-zinc-700'}`}></div>
            VTT_Encoder: {isVttMode ? 'Active' : 'Standby'}
          </span>
        </div>
        <div>
          Design_Ref: Terminal-U_v2.0_Linear_VTT
        </div>
      </footer>
    </div>
  );
};

export default App;
