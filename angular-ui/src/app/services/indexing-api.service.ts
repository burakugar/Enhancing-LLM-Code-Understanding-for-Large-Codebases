import {HttpClient} from '@angular/common/http';
import {Injectable} from '@angular/core';
import {Observable} from 'rxjs';
import {IndexRequest, IndexStatusResponse} from '../models/indexing.model';

@Injectable({
  providedIn: 'root'
})
export class IndexingApiService {

  private apiUrl = '/api/v1/index';

  constructor(private http: HttpClient) {
  }

  startIndexing(codebasePath: string): Observable<IndexStatusResponse> {
    const request: IndexRequest = {codebasePath};
    return this.http.post<IndexStatusResponse>(`${this.apiUrl}/start`, request);
  }

  getIndexingStatus(): Observable<IndexStatusResponse> {
    return this.http.get<IndexStatusResponse>(`${this.apiUrl}/status`);
  }

}
