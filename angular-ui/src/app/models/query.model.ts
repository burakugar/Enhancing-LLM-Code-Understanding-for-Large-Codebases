export interface QueryRequest {
  query: string;
  conversationId?: string;
  temperature?: number;
  maxContextSegments?: number;
  minSimilarityScore?: number;
  useReRanker?: boolean;
  reRankerTopN?: number;
  llmMaxNewTokens?: number;
  modelName?: string;

  rerankerModelName?: string;
}

export interface QueryResponse {
  answer: string;
  conversationId: string;
  sources: SourceReference[];
}

export interface SourceReference {
  filePath: string;
  startLine: number;
  endLine: number;
  snippet: string;
  score?: number;
  language?: string;
}
