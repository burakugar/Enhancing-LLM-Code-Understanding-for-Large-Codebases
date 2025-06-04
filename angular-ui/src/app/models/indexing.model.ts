export interface IndexRequest {
  codebasePath: string;
}

export interface IndexStatusResponse {
  jobId?: string;
  status: string;
  progress?: number;
  details?: string;
}
