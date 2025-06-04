import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';

import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';

import { Model, SetupRequest } from '../../models/setup.model';
import { ChatApiService } from '../../services/chat-api.service';

@Component({
  selector: 'app-initial-setup',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatProgressSpinnerModule,
    MatCardModule,
    MatIconModule
  ],
  templateUrl: './initial-setup.component.html',
  styleUrls: ['./initial-setup.component.scss']
})
export class InitialSetupComponent implements OnInit, OnDestroy {
  public setupForm!: FormGroup;
  public isLoading: boolean = false;
  public isCheckingStatus: boolean = true;
  public error: string | null = null;
  public availableModels: Model[] = [];

  private statusSub!: Subscription;
  private saveSub!: Subscription;
  private modelsSub!: Subscription;

  constructor(
    private fb: FormBuilder,
    private router: Router,
    private chatApiService: ChatApiService
  ) {}

  ngOnInit(): void {
    this.setupForm = this.fb.group({
      modelId: ['', Validators.required]
    });
    this.checkSetupStatus();
  }

  checkSetupStatus(): void {
    this.isCheckingStatus = true;
    this.error = null;
    if (this.statusSub) this.statusSub.unsubscribe();
    this.statusSub = this.chatApiService.getSetupStatus().subscribe({
      next: (status) => {
        if (status.isConfigured) {
          this.router.navigate(['/chat']);
        } else {
          this.loadAvailableModels(status.configuredModelId);
        }
      },
      error: (err) => {
        console.error('Error checking setup status:', err);
        this.error = 'Could not verify application status. Please try refreshing.';
        this.isCheckingStatus = false;
      }
    });
  }

  loadAvailableModels(preSelectedModelId?: string): void {
    if (this.modelsSub) this.modelsSub.unsubscribe();
    this.isCheckingStatus = true;
    this.modelsSub = this.chatApiService.getAvailableModels().subscribe({
      next: (models) => {
        this.availableModels = models;
        if (models.length > 0) {
          const modelToSelect = preSelectedModelId || models[0].id;
          this.setupForm.patchValue({ modelId: modelToSelect });
        }
        this.isCheckingStatus = false;
      },
      error: (err) => {
        console.error('Error loading available models:', err);
        this.error = 'Could not load available models. Please try refreshing.';
        this.isCheckingStatus = false;
      }
    });
  }

  onSubmit(): void {
    if (this.setupForm.invalid) {
      this.error = 'Please select a model.';
      Object.values(this.setupForm.controls).forEach(control => {
        control.markAsTouched();
      });
      return;
    }
    this.isLoading = true;
    this.error = null;

    const formData: SetupRequest = {
      modelId: this.setupForm.value.modelId
    };

    if (this.saveSub) this.saveSub.unsubscribe();
    this.saveSub = this.chatApiService.saveSetup(formData).subscribe({
      next: () => {
        this.isLoading = false;
        this.router.navigate(['/chat']);
      },
      error: (err) => {
        console.error('Error saving setup:', err);
        this.error = err.error?.message || 'Failed to save configuration. Please try again.';
        this.isLoading = false;
      }
    });
  }

  ngOnDestroy(): void {
    if (this.statusSub) this.statusSub.unsubscribe();
    if (this.saveSub) this.saveSub.unsubscribe();
    if (this.modelsSub) this.modelsSub.unsubscribe();
  }
}
