import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { delay, tap, map, catchError } from 'rxjs/operators';
import { HistoryResponse } from '../models/history.model';
import { QueryRequest, QueryResponse } from '../models/query.model';
import { Model, SetupRequest, SetupStatusResponse } from '../models/setup.model';

@Injectable({
  providedIn: 'root'
})
export class ChatApiService {

  private queryApiUrl = '/api/v1/query';
  private historyApiUrl = '/api/v1/history';
  private configApiUrl = '/api/v1/config';

  private _isConfigured = false;
  private _configuredModelId: string | undefined = undefined;

  constructor(private http: HttpClient) {
    const storedModelId = localStorage.getItem('configuredModelId');
    if (storedModelId) {
      this._isConfigured = true;
      this._configuredModelId = storedModelId;
    }
  }

  query(request: QueryRequest): Observable<QueryResponse> {
    return this.http.post<QueryResponse>(this.queryApiUrl, request);
  }

  getConversationHistory(conversationId: string): Observable<HistoryResponse> {
    return this.http.get<HistoryResponse>(`${this.historyApiUrl}/${conversationId}`);
  }

  listConversations(): Observable<HistoryResponse[]> {
    return this.http.get<HistoryResponse[]>(this.historyApiUrl);
  }

  deleteConversation(conversationId: string): Observable<void> {
    return this.http.delete<void>(`${this.historyApiUrl}/${conversationId}`);
  }

  deleteAllConversations(): Observable<void> { // New method
    return this.http.delete<void>(`${this.historyApiUrl}/all`);
  }

  getSetupStatus(): Observable<SetupStatusResponse> {
    console.log('ChatApiService: getSetupStatus called');
    const status: SetupStatusResponse = {
      isConfigured: this._isConfigured,
      configuredModelId: this._isConfigured ? this._configuredModelId : undefined,
    };
    return of(status).pipe(delay(200));
  }

  getAvailableModels(): Observable<Model[]> {
    return this.http.get<Model[]>(`${this.configApiUrl}/models`).pipe(
      map(models => {
        if (!Array.isArray(models)) {
          console.warn('/config/models did not return an array, returning empty. Response:', models);
          return [];
        }
        return models;
      }),
      catchError(err => {
        console.error('Error fetching available models from backend:', err);
        return of([]);
      })
    );
  }

  saveSetup(setupData: SetupRequest): Observable<void> {
    console.log('ChatApiService: saveSetup called with', setupData);
    return of(undefined).pipe(
      delay(1000),
      tap(() => {
        this._isConfigured = true;
        this._configuredModelId = setupData.modelId;
        localStorage.setItem('configuredModelId', setupData.modelId);
        if (setupData.apiKey) {
          console.log('API Key was provided (optional feature).');
        }
      })
    );
  }

  isSetupComplete(): Observable<boolean> {
    if (!this._isConfigured) {
      const storedModelId = localStorage.getItem('configuredModelId');
      if (storedModelId) {
        this._isConfigured = true;
        this._configuredModelId = storedModelId;
      }
    }
    return of(this._isConfigured);
  }
}
