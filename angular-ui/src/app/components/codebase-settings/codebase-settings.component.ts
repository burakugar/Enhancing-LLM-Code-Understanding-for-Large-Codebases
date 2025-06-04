import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCardModule } from '@angular/material/card';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { IndexingApiService } from '../../services/indexing-api.service';
import { IndexStatusResponse } from '../../models/indexing.model';
import { Subscription, interval, startWith, switchMap, takeWhile, tap } from 'rxjs';
import { MatProgressBarModule } from '@angular/material/progress-bar';

@Component({
  selector: 'app-codebase-settings',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatCardModule,
    MatSnackBarModule,
    MatProgressBarModule
  ],
  templateUrl: './codebase-settings.component.html',
  styleUrls: ['./codebase-settings.component.scss']
})
export class CodebaseSettingsComponent implements OnInit, OnDestroy {
  codebaseForm!: FormGroup;
  isLoading: boolean = false;
  indexingStatus: IndexStatusResponse | null = null;
  statusSubscription: Subscription | null = null;
  isPollingStatus: boolean = false;
  private currentCodebasePath: string = '';

  constructor(
    private fb: FormBuilder,
    private indexingApiService: IndexingApiService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.codebaseForm = this.fb.group({
      codebasePath: [localStorage.getItem('codebasePath') || '', [Validators.required, Validators.minLength(3)]]
    });
    this.fetchCurrentStatus();
  }

  fetchCurrentStatus(): void {
    this.isLoading = true;
    this.indexingApiService.getIndexingStatus().subscribe({
      next: (status) => {
        this.isLoading = false;
        this.indexingStatus = status;
        if (status.details && status.details.includes("Path: ")) {
          const pathFromStatus = status.details.split("Path: ")[1]?.split(" ")[0];
          if (pathFromStatus && !this.codebaseForm.get('codebasePath')?.value) {
            this.codebaseForm.get('codebasePath')?.setValue(pathFromStatus);
          }
        }
        if (status.status === 'RUNNING' || status.status === 'STARTED') {
          this.startPollingStatus();
        }
      },
      error: (err) => {
        this.isLoading = false;
        console.error('Error fetching initial indexing status:', err);
        this.snackBar.open('Could not fetch indexing status.', 'Close', { duration: 3000, panelClass: ['error-snackbar'] });
      }
    });
  }

  onStartIndexing(): void {
    if (this.codebaseForm.invalid) {
      this.snackBar.open('Please provide a valid codebase path.', 'Close', { duration: 3000, panelClass: ['error-snackbar'] });
      return;
    }
    this.isLoading = true;
    const path = this.codebaseForm.value.codebasePath;
    localStorage.setItem('codebasePath', path);

    this.indexingApiService.startIndexing(path).subscribe({
      next: (response) => {
        this.isLoading = false;
        this.indexingStatus = response;
        this.currentCodebasePath = path;
        this.snackBar.open(`Indexing process initiated for: ${path}`, 'OK', { duration: 3000, panelClass: ['success-snackbar'] });
        if (response.status === 'STARTED' || response.status === 'RUNNING') {
          this.startPollingStatus();
        }
      },
      error: (err) => {
        this.isLoading = false;
        this.indexingStatus = err.error as IndexStatusResponse || { status: 'FAILED', details: 'Unknown error during start.', progress: 0 };
        console.error('Error starting indexing:', err);
        this.snackBar.open(`Error starting indexing: ${err.error?.details || err.message}`, 'Close', { duration: 5000, panelClass: ['error-snackbar'] });
      }
    });
  }

  startPollingStatus(): void {
    if (this.isPollingStatus) return;
    this.isPollingStatus = true;
    console.log('Starting status polling...');

    this.statusSubscription = interval(3000)
      .pipe(
        startWith(0),
        switchMap(() => this.indexingApiService.getIndexingStatus()),
        tap(status => console.log('Polled status:', status)),
        takeWhile(status => status.status === 'RUNNING' || status.status === 'STARTED', true)
      )
      .subscribe({
        next: (status) => {
          this.indexingStatus = status;
          if (status.status !== 'RUNNING' && status.status !== 'STARTED') {
            this.stopPollingStatus();
            if (status.status === 'COMPLETED') {
              this.snackBar.open('Indexing completed successfully!', 'OK', { duration: 4000, panelClass: ['success-snackbar'] });
            } else if (status.status === 'FAILED') {
              this.snackBar.open(`Indexing failed: ${status.details || 'Unknown reason'}`, 'Close', { duration: 5000, panelClass: ['error-snackbar'] });
            }
          }
        },
        error: (err) => {
          console.error('Error polling indexing status:', err);
          this.snackBar.open('Error polling status. Check console.', 'Close', { duration: 3000, panelClass: ['error-snackbar'] });
          this.stopPollingStatus();
        }
      });
  }

  stopPollingStatus(): void {
    if (this.statusSubscription) {
      this.statusSubscription.unsubscribe();
      this.statusSubscription = null;
    }
    this.isPollingStatus = false;
    console.log('Status polling stopped.');
  }

  // Getter for the progress bar value
  get progressValue(): number {
    return (this.indexingStatus && typeof this.indexingStatus.progress === 'number')
      ? this.indexingStatus.progress * 100
      : 0;
  }

  // Getter to determine if the progress bar should be visible
  get showProgressBar(): boolean {
    return !!(this.indexingStatus &&
      this.indexingStatus.status === 'RUNNING' &&
      typeof this.indexingStatus.progress === 'number' &&
      this.indexingStatus.progress > 0);
  }

  // Getter for the detailed status string
  get progressDetails(): string {
    if (!this.indexingStatus) return 'Status unknown.';
    let details = `Status: ${this.indexingStatus.status}`;
    if (this.indexingStatus.status === 'RUNNING' &&
      typeof this.indexingStatus.progress === 'number' &&
      this.indexingStatus.progress > 0) {
      details += ` (${(this.indexingStatus.progress * 100).toFixed(1)}%)`;
    }
    if (this.indexingStatus.details) {
      details += ` - ${this.indexingStatus.details}`;
    }
    return details;
  }

  ngOnDestroy(): void {
    this.stopPollingStatus();
  }
}
