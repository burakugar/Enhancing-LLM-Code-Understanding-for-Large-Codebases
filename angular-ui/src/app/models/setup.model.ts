export interface Model {
  id: string;
  name: string;
}

export interface SetupStatusResponse {
  isConfigured: boolean;
  configuredModelId?: string;
  availableModels?: Model[]; // Backend might return models along with status
}

export interface SetupRequest {
  apiKey?: string; // Made apiKey optional
  modelId: string;
}
