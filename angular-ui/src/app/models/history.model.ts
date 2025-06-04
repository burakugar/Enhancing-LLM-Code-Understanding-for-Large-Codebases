import { SourceReference } from './query.model';

export interface HistoryMessage {
  role: 'user' | 'assistant' | 'system' | 'error';
  content: string;
  timestamp: string;
  sources?: SourceReference[];
}

export interface HistoryResponse {
  conversationId: string;
  title?: string;
  createdAt?: string;     // ISO 8601 format
  updatedAt?: string;     // ISO 8601 format
  messages: HistoryMessage[];
}
