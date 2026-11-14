# File: wavEncoder.ts

```typescript
// Utility to convert AudioBuffer to WAV Blob
// This ensures that when we slice audio, we send valid WAV files with headers to Gemini.

export function bufferToWav(abuffer: AudioBuffer, len: number): Blob {
  const numOfChan = abuffer.numberOfChannels;
  const length = len * numOfChan * 2 + 44;
  const buffer = new ArrayBuffer(length);
  const view = new DataView(buffer);
  const channels = [];
  let i;
  let sample;
  let offset = 0;
  let pos = 0;

  // write WAVE header
  setUint32(0x46464952); // "RIFF"
  setUint32(length - 8); // file length - 8
  setUint32(0x45564157); // "WAVE"

  setUint32(0x20746d66); // "fmt " chunk
  setUint32(16); // length = 16
  setUint16(1); // PCM (uncompressed)
  setUint16(numOfChan);
  setUint32(abuffer.sampleRate);
  setUint32(abuffer.sampleRate * 2 * numOfChan); // avg. bytes/sec
  setUint16(numOfChan * 2); // block-align
  setUint16(16); // 16-bit (hardcoded in this encoder)

  setUint32(0x61746164); // "data" - chunk
  setUint32(length - pos - 4); // chunk length

  // write interleaved data
  for (i = 0; i < abuffer.numberOfChannels; i++)
    channels.push(abuffer.getChannelData(i));

  while (pos < length) {
    for (i = 0; i < numOfChan; i++) {
      // interleave channels
      sample = Math.max(-1, Math.min(1, channels[i][offset])); // clamp
      sample = (0.5 + sample < 0 ? sample * 32768 : sample * 32767) | 0; // scale to 16-bit signed int
      view.setInt16(pos, sample, true); // write 16-bit sample
      pos += 2;
    }
    offset++; // next source sample
  }

  return new Blob([buffer], { type: 'audio/wav' });

  function setUint16(data: number) {
    view.setUint16(pos, data, true);
    pos += 2;
  }

  function setUint32(data: number) {
    view.setUint32(pos, data, true);
    pos += 4;
  }
}

// Helper to slice an AudioBuffer and return a Blob
export function sliceAudioBuffer(
  audioBuffer: AudioBuffer,
  startTime: number,
  endTime: number,
  audioContext: AudioContext
): Blob {
  const sampleRate = audioBuffer.sampleRate;
  const startFrame = Math.floor(startTime * sampleRate);
  const endFrame = Math.floor(endTime * sampleRate);
  const frameCount = endFrame - startFrame;
  
  if (frameCount <= 0) {
      throw new Error("Invalid slice duration");
  }

  const newBuffer = audioContext.createBuffer(
    audioBuffer.numberOfChannels,
    frameCount,
    sampleRate
  );

  for (let i = 0; i < audioBuffer.numberOfChannels; i++) {
    const channelData = audioBuffer.getChannelData(i);
    const newChannelData = newBuffer.getChannelData(i);
    // Copy the slice
    for (let j = 0; j < frameCount; j++) {
       newChannelData[j] = channelData[startFrame + j];
    }
  }

  return bufferToWav(newBuffer, frameCount);
}

// Highly optimized slice and downsample to 16000Hz mono WAV
export async function sliceAndDownsampleAudio(
  audioBuffer: AudioBuffer,
  startTime: number,
  endTime: number
): Promise<Blob> {
  const targetSampleRate = 16000;
  const duration = endTime - startTime;
  const targetFrameCount = Math.floor(duration * targetSampleRate);

  if (targetFrameCount <= 0) {
    throw new Error("Invalid slice duration");
  }

  const OfflineContext = window.OfflineAudioContext || (window as any).webkitOfflineAudioContext;
  if (!OfflineContext) {
    throw new Error("OfflineAudioContext not supported in this browser environment");
  }

  const offlineCtx = new OfflineContext(
    1, // mono
    targetFrameCount,
    targetSampleRate
  );

  const bufferSource = offlineCtx.createBufferSource();
  bufferSource.buffer = audioBuffer;
  bufferSource.connect(offlineCtx.destination);
  bufferSource.start(0, startTime, duration);

  const renderedBuffer = await offlineCtx.startRendering();
  return bufferToWav(renderedBuffer, renderedBuffer.length);
}
```

