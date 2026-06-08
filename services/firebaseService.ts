import { initializeApp } from 'firebase/app';
import { getFirestore, collection, addDoc, getDocs, query, orderBy, doc, getDoc, updateDoc } from 'firebase/firestore';
import { getStorage, ref, uploadBytesResumable, getDownloadURL } from 'firebase/storage';
import firebaseConfig from '../firebase-applet-config.json';
import { SystemLog } from '../types';

const app = initializeApp(firebaseConfig);
export const db = getFirestore(app, firebaseConfig.firestoreDatabaseId);
export const storage = getStorage(app);

export interface TranscriptionSession {
  id?: string;
  name: string;
  description: string;
  tags: string[];
  transcript: string; // Acts as raw_text for backwards compatibility
  formatted_text?: string;
  category?: string;
  topicCluster?: string;
  raw_text?: string;
  audioUrl?: string;
  createdAt: number;
  is_successful?: boolean;
  block_history_formatted?: any[];
  block_history_raw?: any[];
}

export const uploadAudioToStorage = async (
  file: Blob | File,
  timestamp: number,
  onProgress?: (progress: number) => void
): Promise<string> => {
  const fileExt = file instanceof File ? file.name.split('.').pop() : 'webm';
  const storageRef = ref(storage, `recordings/recording-${timestamp}.${fileExt}`);
  const uploadTask = uploadBytesResumable(storageRef, file);

  return new Promise((resolve, reject) => {
    uploadTask.on(
      'state_changed',
      (snapshot) => {
        const progress = (snapshot.bytesTransferred / snapshot.totalBytes) * 100;
        if (onProgress) onProgress(progress);
      },
      (error) => {
        reject(error);
      },
      async () => {
        const downloadURL = await getDownloadURL(uploadTask.snapshot.ref);
        resolve(downloadURL);
      }
    );
  });
};

export const saveSessionToFirestore = async (session: Omit<TranscriptionSession, 'id'>): Promise<string> => {
  try {
    const docRef = await addDoc(collection(db, 'sessions'), session);
    return docRef.id;
  } catch (error) {
    console.error('Error saving session:', error);
    throw error;
  }
};

export const getAllSessions = async (): Promise<TranscriptionSession[]> => {
  try {
    const q = query(collection(db, 'sessions'), orderBy('createdAt', 'desc'));
    const querySnapshot = await getDocs(q);
    return querySnapshot.docs.map(doc => ({
      id: doc.id,
      ...doc.data()
    })) as TranscriptionSession[];
  } catch (error) {
    console.error('Error fetching sessions:', error);
    throw error;
  }
};

export const updateSessionInFirestore = async (id: string, updates: Partial<TranscriptionSession>): Promise<void> => {
  try {
    const docRef = doc(db, 'sessions', id);
    await updateDoc(docRef, updates);
  } catch (error) {
    console.error('Error updating session:', error);
    throw error;
  }
};

export const deleteSessionInFirestore = async (id: string): Promise<void> => {
  try {
    const docRef = doc(db, 'sessions', id);
    const { deleteDoc } = await import('firebase/firestore');
    await deleteDoc(docRef);
  } catch (error) {
    console.error('Error deleting session:', error);
    throw error;
  }
};
