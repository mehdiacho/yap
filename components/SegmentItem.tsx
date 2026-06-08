import React from 'react';
import { Play, Trash2, FileAudio, CheckCircle, Loader2, AlertCircle } from 'lucide-react';
import { AudioSegment } from '../types';

interface SegmentItemProps {
  segment: AudioSegment;
  index: number;
  onPlay: (segment: AudioSegment) => void;
  onDelete: (id: string) => void;
}

const SegmentItem: React.FC<SegmentItemProps> = ({ segment, index, onPlay, onDelete }) => {
  const formatTime = (time: number) => {
     return new Date(time * 1000).toISOString().substr(14, 5);
  };

  const getStatusIcon = () => {
    switch (segment.status) {
      case 'COMPLETED': return <CheckCircle size={14} className="text-green-500" />;
      case 'PROCESSING': return <Loader2 size={14} className="text-cyan-500 animate-spin" />;
      case 'ERROR': return <AlertCircle size={14} className="text-red-500" />;
      default: return <div className="w-3 h-3 rounded-full bg-zinc-700" />;
    }
  };

  return (
    <div className="group flex items-center justify-between p-3 bg-zinc-950 border border-zinc-800 rounded mb-2 hover:border-cyan-900 transition-colors">
      <div className="flex items-center gap-3">
        <div className="font-mono text-xs text-zinc-600 w-6">
          {index.toString().padStart(2, '0')}
        </div>
        <FileAudio size={16} className="text-zinc-500" />
        <div className="flex flex-col">
          <span className="font-mono text-xs text-cyan-600">
            SEG_{segment.id.split('-')[0].toUpperCase()}
          </span>
          <span className="text-xs text-zinc-400 font-mono">
            {formatTime(segment.startTime)} - {formatTime(segment.endTime)}
          </span>
        </div>
      </div>

      <div className="flex items-center gap-2">
        <div className="mr-2" title={segment.status}>{getStatusIcon()}</div>
        
        <button
          onClick={() => onPlay(segment)}
          className="p-1.5 text-zinc-400 hover:text-cyan-400 hover:bg-zinc-900 rounded transition-colors"
          title="Audit Segment"
        >
          <Play size={14} />
        </button>
        <button
          onClick={() => onDelete(segment.id)}
          className="p-1.5 text-zinc-400 hover:text-red-500 hover:bg-zinc-900 rounded transition-colors"
          title="Purge Segment"
        >
          <Trash2 size={14} />
        </button>
      </div>
    </div>
  );
};

export default SegmentItem;