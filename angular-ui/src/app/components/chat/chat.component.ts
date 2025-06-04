import { CommonModule, DatePipe } from '@angular/common';
import { AfterViewChecked, Component, ElementRef, OnDestroy, OnInit, ViewChild, ChangeDetectorRef, NgZone } from '@angular/core';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { Subscription, Subject } from 'rxjs';
import { debounceTime, filter } from 'rxjs/operators';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { CdkTextareaAutosize, TextFieldModule } from '@angular/cdk/text-field';
import { MatDividerModule } from '@angular/material/divider';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatMenuModule } from '@angular/material/menu';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSliderModule } from '@angular/material/slider';

import { HistoryMessage } from '../../models/history.model';
import { QueryRequest, SourceReference } from '../../models/query.model';
import { Model } from '../../models/setup.model';
import { ChatApiService } from '../../services/chat-api.service';

import { MarkdownModule } from 'ngx-markdown';
import { HighlightModule } from 'ngx-highlightjs';


interface ChatQuerySettings {
  temperature: number;
  llmMaxNewTokens: number;
  modelId?: string;
  useReRanker?: boolean;
  rerankerModelName?: string;
  reRankerTopN?: number;
}

@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    RouterModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatCardModule,
    MatSnackBarModule,
    TextFieldModule,
    MatSelectModule,
    MatMenuModule,
    MatExpansionModule,
    MatSlideToggleModule,
    MatCheckboxModule,
    MatDividerModule,
    MatSliderModule,
    MarkdownModule,
    HighlightModule
  ],
  providers: [DatePipe],
  templateUrl: './chat.component.html',
  styleUrls: ['./chat.component.scss']
})
export class ChatComponent implements OnInit, OnDestroy, AfterViewChecked {
  @ViewChild('chatHistoryPanel') private chatHistoryPanel!: ElementRef;
  @ViewChild('autosize') autosize!: CdkTextareaAutosize;
  @ViewChild('messageInput') messageInput!: ElementRef;

  public messages: HistoryMessage[] = [];
  public currentConversationId: string | undefined = undefined;
  public isLoading: boolean = false;
  public userInput: string = '';
  public editingMessageIndex: number = -1;
  public editedMessageContent: string = '';
  public shouldScrollToBottom: boolean = true;
  private userHasScrolled: boolean = false;

  public defaultQuerySettings: Readonly<ChatQuerySettings> = {
    temperature: 0.7,
    llmMaxNewTokens: 1024,
    modelId: undefined,
    useReRanker: true,
    rerankerModelName: undefined,
    reRankerTopN: 3,
  };
  public querySettings: ChatQuerySettings = { ...this.defaultQuerySettings };
  public availableModels: Model[] = [];
  private globallyConfiguredModelId?: string;

  private routeSub!: Subscription;
  private querySub!: Subscription;
  private modelsSub!: Subscription;
  private statusSub!: Subscription;
  private settingsChangeSubject = new Subject<void>();
  private refreshConversationsSub!: Subscription;

  constructor(
    private chatApiService: ChatApiService,
    private route: ActivatedRoute,
    private router: Router,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef,
    private ngZone: NgZone
  ) {}

  ngOnInit(): void {
    this.loadGlobalConfigAndModels();

    this.routeSub = this.route.paramMap.subscribe(params => {
      const id = params.get('id');
      if (id) {
        if (id !== this.currentConversationId || this.messages.length === 0) {
          this.currentConversationId = id;
          this.loadConversation(id);
          this.shouldScrollToBottom = true;
          this.userHasScrolled = false;
        }
      } else {
        this.resetChat();
      }
    });

    this.settingsChangeSubject.pipe(debounceTime(300)).subscribe(() => {
      this.saveSettings();
      this.snackBar.open('Settings saved', 'Close', {
        duration: 2000,
        panelClass: ['success-snackbar']
      });
    });

    // Setup interval to refresh conversations list
    this.refreshConversationList();
  }

  ngAfterViewChecked(): void {
    if (this.shouldScrollToBottom && !this.userHasScrolled) {
      this.scrollToBottom();
      this.shouldScrollToBottom = false;
    }
  }

  onChatScroll(): void {
    if (this.chatHistoryPanel) {
      const element = this.chatHistoryPanel.nativeElement;
      const isAtBottom = element.scrollHeight - element.scrollTop <= element.clientHeight + 100;
      this.userHasScrolled = !isAtBottom;
    }
  }

  focusMessageInput(): void {
    if (this.messageInput && this.messageInput.nativeElement) {
      setTimeout(() => {
        this.messageInput.nativeElement.focus();
      }, 100);
    }
  }

  loadGlobalConfigAndModels(): void {
    this.isLoading = true;
    this.modelsSub = this.chatApiService.getAvailableModels().subscribe({
      next: (models) => {
        this.availableModels = models;
        this.statusSub = this.chatApiService.getSetupStatus().subscribe({
          next: (status) => {
            this.globallyConfiguredModelId = status.configuredModelId;
            this.loadSettings();
            this.isLoading = false;
            this.cdr.detectChanges();
          },
          error: (err) => {
            console.error('Error fetching setup status:', err);
            this.loadSettings();
            this.isLoading = false;
            this.cdr.detectChanges();
          }
        });
      },
      error: (err) => {
        console.error('Error loading available models:', err);
        this.snackBar.open('Could not load LLM models. Settings may be incomplete.', 'Close', {
          duration: 3000,
          panelClass: ['error-snackbar']
        });
        this.loadSettings();
        this.isLoading = false;
        this.cdr.detectChanges();
      }
    });
  }

  loadConversation(conversationId: string): void {
    this.isLoading = true;
    this.messages = [];
    this.chatApiService.getConversationHistory(conversationId).subscribe({
      next: (historyResponse) => {
        this.messages = historyResponse.messages?.map(m => ({
          ...m,
          timestamp: new Date(m.timestamp).toISOString()
        })) || [];
        this.currentConversationId = historyResponse.conversationId;
        this.isLoading = false;
        this.cdr.detectChanges();
        this.shouldScrollToBottom = true;
      },
      error: (err) => {
        console.error('Error fetching conversation history:', err);
        this.snackBar.open(`Error loading conversation: ${err.error?.detail || err.message}`, 'Close', {
          duration: 5000,
          panelClass: ['error-snackbar']
        });
        this.isLoading = false;
        if (err.status === 404) {
          this.router.navigate(['/chat']);
        }
        this.cdr.detectChanges();
      }
    });
  }

  resetChat(): void {
    this.messages = [];
    this.currentConversationId = undefined;
    this.userInput = '';
    this.isLoading = false;
    this.editingMessageIndex = -1;
    this.editedMessageContent = '';
    this.userHasScrolled = false;
    if (this.route.snapshot.paramMap.get('id')) {
      this.router.navigate(['/chat']);
    }
    this.loadSettings();
    this.cdr.detectChanges();

    // Focus the input field when chat is reset
    this.focusMessageInput();
  }

  onEnterPress(event: Event): void {
    const keyboardEvent = event as KeyboardEvent;
    if (keyboardEvent.key === 'Enter' && !keyboardEvent.shiftKey) {
      keyboardEvent.preventDefault();
      this.sendMessage();
    }
  }

  sendMessage(): void {
    if (!this.userInput.trim() || this.isLoading || this.editingMessageIndex !== -1) return;

    const userMessage: HistoryMessage = {
      role: 'user',
      content: this.userInput,
      timestamp: new Date().toISOString()
    };
    this.messages.push(userMessage);
    this.shouldScrollToBottom = true;
    this.userHasScrolled = false;

    const request: QueryRequest = {
      query: this.userInput,
      conversationId: this.currentConversationId,
      temperature: this.querySettings.temperature,
      llmMaxNewTokens: this.querySettings.llmMaxNewTokens,
      modelName: this.querySettings.modelId,
      useReRanker: this.querySettings.useReRanker,
      rerankerModelName: this.querySettings.rerankerModelName,
      reRankerTopN: this.querySettings.reRankerTopN
    };
    this.isLoading = true;
    const currentInput = this.userInput;
    this.userInput = '';
    this.triggerResize();

    if (this.querySub) this.querySub.unsubscribe();
    this.querySub = this.chatApiService.query(request).subscribe({
      next: (response) => {
        const assistantMessage: HistoryMessage = {
          role: 'assistant',
          content: response.answer,
          sources: response.sources,
          timestamp: new Date().toISOString()
        };
        this.messages.push(assistantMessage);

        if (!this.currentConversationId && response.conversationId) {
          this.currentConversationId = response.conversationId;
          this.router.navigate(['/chat', this.currentConversationId], { replaceUrl: true });
          // Trigger refresh of conversation list
          this.refreshConversationList();
        }
        this.isLoading = false;
        this.cdr.detectChanges();
        this.shouldScrollToBottom = true;
        this.userHasScrolled = false;
        this.focusMessageInput();
      },
      error: (err) => {
        console.error('Error sending message:', err);
        const errorContent = err.error?.detail || err.error?.message || err.message || 'Failed to get response.';
        this.messages.push({
          role: 'error',
          content: `Error: ${errorContent}`,
          timestamp: new Date().toISOString()
        });
        this.userInput = currentInput;
        this.isLoading = false;
        this.snackBar.open(errorContent, 'Close', {
          duration: 5000,
          panelClass: ['error-snackbar']
        });
        this.cdr.detectChanges();
        this.shouldScrollToBottom = true;
        this.focusMessageInput();
      }
    });
  }

  clearChat(): void {
    this.resetChat();
    this.snackBar.open('Chat cleared', 'Close', {
      duration: 2000,
      panelClass: ['success-snackbar']
    });
  }

  onSettingChange(): void {
    this.saveSettings();
    this.snackBar.open('Settings saved', 'Close', {
      duration: 1500,
      panelClass: ['success-snackbar']
    });
  }

  onSettingChangeDebounced(): void {
    this.settingsChangeSubject.next();
  }

  formatTemperature(value: number): string {
    return value.toFixed(1);
  }

  // Message editing functionality
  startEditingMessage(index: number): void {
    if (index < 0 || index >= this.messages.length || this.messages[index].role !== 'user') {
      return;
    }

    this.editingMessageIndex = index;
    this.editedMessageContent = this.messages[index].content;
    this.cdr.detectChanges();
  }

  saveEditedMessage(): void {
    if (this.editingMessageIndex === -1 || !this.editedMessageContent.trim()) {
      this.cancelEditingMessage();
      return;
    }

    const currentIndex = this.editingMessageIndex;
    // Update the message content
    this.messages[currentIndex].content = this.editedMessageContent;
    this.messages[currentIndex].timestamp = new Date().toISOString();

    // Need to remove all following messages and re-send query
    const editedMsg = this.messages[currentIndex];
    const conversationBeforeEdit = this.messages.slice(0, currentIndex + 1);

    // Cancel editing mode
    this.editingMessageIndex = -1;
    this.editedMessageContent = '';

    // Reset messages to only include up to the edited message
    this.messages = conversationBeforeEdit;

    // Now simulate sending the edited message
    this.isLoading = true;

    const request: QueryRequest = {
      query: editedMsg.content,
      conversationId: this.currentConversationId,
      temperature: this.querySettings.temperature,
      llmMaxNewTokens: this.querySettings.llmMaxNewTokens,
      modelName: this.querySettings.modelId,
      useReRanker: this.querySettings.useReRanker,
      rerankerModelName: this.querySettings.rerankerModelName,
      reRankerTopN: this.querySettings.reRankerTopN
    };

    if (this.querySub) this.querySub.unsubscribe();
    this.querySub = this.chatApiService.query(request).subscribe({
      next: (response) => {
        const assistantMessage: HistoryMessage = {
          role: 'assistant',
          content: response.answer,
          sources: response.sources,
          timestamp: new Date().toISOString()
        };
        this.messages.push(assistantMessage);

        this.isLoading = false;
        this.cdr.detectChanges();
        this.shouldScrollToBottom = true;
        this.userHasScrolled = false;
        this.focusMessageInput();
      },
      error: (err) => {
        console.error('Error after editing message:', err);
        const errorContent = err.error?.detail || err.error?.message || err.message || 'Failed to get response after edit.';
        this.messages.push({
          role: 'error',
          content: `Error: ${errorContent}`,
          timestamp: new Date().toISOString()
        });

        this.isLoading = false;
        this.snackBar.open(errorContent, 'Close', {
          duration: 5000,
          panelClass: ['error-snackbar']
        });
        this.cdr.detectChanges();
        this.shouldScrollToBottom = true;
        this.focusMessageInput();
      }
    });
  }

  cancelEditingMessage(): void {
    this.editingMessageIndex = -1;
    this.editedMessageContent = '';
    this.cdr.detectChanges();
    this.focusMessageInput();
  }

  // Periodically refresh conversation list to keep it in sync
  private refreshConversationList(): void {
    // Event to notify conversation list about changes
    const refreshEvent = new CustomEvent('refresh-conversations', {
      bubbles: true,
      detail: { timestamp: new Date().toISOString() }
    });

    // Dispatch event to refresh conversations
    document.dispatchEvent(refreshEvent);
  }

  private saveSettings(): void {
    localStorage.setItem('chatQuerySettings', JSON.stringify(this.querySettings));
  }

  private loadSettings(): void {
    const storedSettings = localStorage.getItem('chatQuerySettings');
    let parsedSettings: Partial<ChatQuerySettings> = {};

    if (storedSettings) {
      try {
        parsedSettings = JSON.parse(storedSettings);
      } catch (e) {
        console.error("Failed to parse stored query settings, using defaults.", e);
        localStorage.removeItem('chatQuerySettings');
      }
    }

    this.querySettings = {
      ...this.defaultQuerySettings,
      ...parsedSettings
    };

    // Ensure chat model ID is valid or default
    if (!this.querySettings.modelId || !this.availableModels.find(m => m.id === this.querySettings.modelId)) {
      this.querySettings.modelId = this.globallyConfiguredModelId ||
        (this.availableModels.length > 0 ? this.availableModels[0].id : undefined);
    }

    // Ensure reranker model ID is valid or default
    if (!this.querySettings.rerankerModelName || !this.availableModels.find(m => m.id === this.querySettings.rerankerModelName)) {
      const defaultReranker = this.availableModels.find(m => m.id.includes('codellama')) ||
        (this.availableModels.length > 0 ? this.availableModels[0] : undefined);
      this.querySettings.rerankerModelName = defaultReranker ? defaultReranker.id : undefined;
    }

    // Validate other settings
    if (typeof this.querySettings.useReRanker !== 'boolean') {
      this.querySettings.useReRanker = this.defaultQuerySettings.useReRanker;
    }
    if (typeof this.querySettings.reRankerTopN !== 'number' || this.querySettings.reRankerTopN < 1) {
      this.querySettings.reRankerTopN = this.defaultQuerySettings.reRankerTopN;
    }
    if (typeof this.querySettings.temperature !== 'number' || this.querySettings.temperature < 0) {
      this.querySettings.temperature = this.defaultQuerySettings.temperature;
    }
    if (typeof this.querySettings.llmMaxNewTokens !== 'number' || this.querySettings.llmMaxNewTokens < 1) {
      this.querySettings.llmMaxNewTokens = this.defaultQuerySettings.llmMaxNewTokens;
    }

    this.cdr.detectChanges();
  }

  private scrollToBottom(): void {
    try {
      if (this.chatHistoryPanel?.nativeElement) {
        this.chatHistoryPanel.nativeElement.scrollTop = this.chatHistoryPanel.nativeElement.scrollHeight;
      }
    } catch (err) {
      console.warn("Scroll to bottom failed:", err);
    }
  }

  triggerResize() {
    this.ngZone.onStable.pipe(debounceTime(100)).subscribe(() => {
      try {
        if (this.autosize) {
          this.autosize.resizeToFitContent(true);
        }
      } catch (e) {
        console.warn("Textarea resize failed", e);
      }
    });
  }

  ngOnDestroy(): void {
    if (this.routeSub) this.routeSub.unsubscribe();
    if (this.querySub) this.querySub.unsubscribe();
    if (this.modelsSub) this.modelsSub.unsubscribe();
    if (this.statusSub) this.statusSub.unsubscribe();
    if (this.refreshConversationsSub) this.refreshConversationsSub.unsubscribe();
  }
}
