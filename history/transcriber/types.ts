
// Fix: Defined AudioSegment interface to resolve compilation errors in UI components
export interface AudioSegment {
  id: string;
  startTime: number;
  endTime: number;
  status: 'IDLE' | 'PROCESSING' | 'COMPLETED' | 'ERROR';
  transcript?: string;
}

export interface ProcessingSegment {
  id: string;
  startTime: number;
  endTime: number;
  duration: number;
  blob: Blob;
}

export interface SystemLog {
  id: string;
  timestamp: string;
  message: string;
  type: 'INFO' | 'PROCESS' | 'SUCCESS' | 'ERROR';
}

export enum AppStatus {
  IDLE = 'IDLE',
  LOADING = 'LOADING',
  READY = 'READY',
  RECORDING = 'RECORDING',
  PROCESSING = 'PROCESSING',
  COMPLETED = 'COMPLETED',
  ERROR = 'ERROR'
}
