import {CommonModule} from '@angular/common';
import {Component, OnDestroy, OnInit} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {MatButtonModule} from '@angular/material/button';
import {MatDividerModule} from '@angular/material/divider';
import {MatFormFieldModule} from '@angular/material/form-field';
import {MatIconModule} from '@angular/material/icon';
import {MatInputModule} from '@angular/material/input';
import {MatListModule} from '@angular/material/list';
import {MatProgressSpinnerModule} from '@angular/material/progress-spinner';
import {MatSnackBar, MatSnackBarModule} from '@angular/material/snack-bar';
import {MatTooltipModule} from '@angular/material/tooltip';
import {NavigationEnd, Router, RouterModule} from '@angular/router';
import {Subject, Subscription} from 'rxjs';
import {debounceTime, distinctUntilChanged, filter} from 'rxjs/operators';
import {HistoryResponse} from '../../models/history.model';
import {ChatApiService} from '../../services/chat-api.service';

@Component({
  selector: 'app-conversation-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    FormsModule,
    MatListModule,
    MatDividerModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatFormFieldModule,
    MatInputModule,
    MatTooltipModule
  ],
  templateUrl: './conversation-list.component.html',
  styleUrls: ['./conversation-list.component.scss']
})
export class ConversationListComponent implements OnInit, OnDestroy {
  public conversations: HistoryResponse[] = [];
  public filteredConversations: HistoryResponse[] = [];
  public isLoading: boolean = true;
  public error: string | null = null;
  public currentConversationId: string | null = null;
  public searchTerm: string = '';

  private searchTermChanged = new Subject<string>();

  private routerSub!: Subscription;
  private conversationLoadSub!: Subscription;
  private deleteConversationSub!: Subscription;
  private deleteAllSub!: Subscription;
  private refreshEventSub!: Subscription;
  private searchSub!: Subscription;
  private autoRefreshInterval: any;

  constructor(
    private chatApiService: ChatApiService,
    private router: Router,
    private snackBar: MatSnackBar
  ) {
  }

  ngOnInit(): void {
    this.loadConversations();

    this.searchSub = this.searchTermChanged.pipe(
      debounceTime(300),
      distinctUntilChanged()
    ).subscribe(term => {
      this.searchTerm = term;
      this.filterConversations();
    });

    this.routerSub = this.router.events.pipe(
      filter(event => event instanceof NavigationEnd)
    ).subscribe(() => {
      this.updateCurrentConversationIdFromUrl();
    });
    this.updateCurrentConversationIdFromUrl();

    this.refreshEventSub = this.createRefreshEventListener();

    this.autoRefreshInterval = setInterval(() => {
      this.loadConversations(false); // Silent refresh
    }, 15000);
  }

  private createRefreshEventListener(): Subscription {
    const eventCallback = () => {
      this.loadConversations(false); // Silent refresh
    };

    document.addEventListener('refresh-conversations', eventCallback);

    return {
      unsubscribe: () => {
        document.removeEventListener('refresh-conversations', eventCallback);
      }
    } as Subscription;
  }

  private updateCurrentConversationIdFromUrl(): void {
    const urlSegments = this.router.url.split('/');
    const chatIndex = urlSegments.indexOf('chat');
    if (chatIndex !== -1 && urlSegments.length > chatIndex + 1) {
      this.currentConversationId = urlSegments[chatIndex + 1];
    } else {
      this.currentConversationId = null;
    }
  }

  loadConversations(showLoadingIndicator: boolean = true): void {
    if (showLoadingIndicator) {
      this.isLoading = true;
    }
    this.error = null;

    if (this.conversationLoadSub) this.conversationLoadSub.unsubscribe();

    this.conversationLoadSub = this.chatApiService.listConversations().subscribe({
      next: (data) => {
        this.conversations = data.sort((a, b) => {
          const dateA = a.updatedAt || (a.messages && a.messages.length > 0 ? a.messages[a.messages.length - 1].timestamp : a.createdAt) || '';
          const dateB = b.updatedAt || (b.messages && b.messages.length > 0 ? b.messages[b.messages.length - 1].timestamp : b.createdAt) || '';
          return new Date(dateB).getTime() - new Date(dateA).getTime();
        });

        this.filterConversations();
        this.isLoading = false;
      },
      error: (err) => {
        console.error('Error fetching conversations:', err);
        this.error = 'Failed to load conversations. Please try again.';
        this.isLoading = false;
      }
    });
  }

  selectConversation(conversationId: string): void {
    if (this.currentConversationId !== conversationId) {
      this.router.navigate(['/chat', conversationId]);
    }
  }

  startNewChat(): void {
    if (this.router.url !== '/chat') {
      this.router.navigate(['/chat']);
    }
    this.currentConversationId = null;
  }

  confirmDeleteConversation(conversationId: string, event: MouseEvent): void {
    event.stopPropagation();
    const confirmed = window.confirm('Are you sure you want to delete this conversation? This action cannot be undone.');
    if (confirmed) {
      this.deleteConversation(conversationId);
    }
  }

  private deleteConversation(conversationId: string): void {
    if (this.deleteConversationSub) this.deleteConversationSub.unsubscribe();
    this.isLoading = true;

    this.deleteConversationSub = this.chatApiService.deleteConversation(conversationId).subscribe({
      next: () => {
        this.snackBar.open('Conversation deleted successfully.', 'Close', {
          duration: 3000,
          panelClass: ['success-snackbar']
        });

        this.conversations = this.conversations.filter(conv => conv.conversationId !== conversationId);
        this.filterConversations();

        if (this.currentConversationId === conversationId) {
          this.router.navigate(['/chat']);
          this.currentConversationId = null;
        }

        this.isLoading = false;
      },
      error: (err) => {
        console.error('Error deleting conversation:', err);
        this.error = 'Failed to delete conversation.';
        this.snackBar.open('Error deleting conversation. Please try again.', 'Close', {
          duration: 3000,
          panelClass: ['error-snackbar']
        });
        this.isLoading = false;
      }
    });
  }

  confirmDeleteAllConversations(): void {
    const confirmed = window.confirm('Are you sure you want to delete ALL conversations? This action cannot be undone.');
    if (confirmed) {
      this.deleteAllConversations();
    }
  }

  private deleteAllConversations(): void {
    if (this.deleteAllSub) this.deleteAllSub.unsubscribe();
    this.isLoading = true;

    this.deleteAllSub = this.chatApiService.deleteAllConversations().subscribe({
      next: () => {
        this.snackBar.open('All conversations deleted successfully.', 'Close', {
          duration: 3000,
          panelClass: ['success-snackbar']
        });

        this.conversations = [];
        this.filteredConversations = [];

        if (this.currentConversationId) {
          this.router.navigate(['/chat']);
          this.currentConversationId = null;
        }

        this.isLoading = false;
      },
      error: (err) => {
        console.error('Error deleting all conversations:', err);
        this.error = 'Failed to delete all conversations.';
        this.snackBar.open('Error deleting all conversations. Please try again.', 'Close', {
          duration: 3000,
          panelClass: ['error-snackbar']
        });
        this.isLoading = false;
      }
    });
  }

  onSearchTermChange(term: string): void {
    this.searchTermChanged.next(term);
  }

  filterConversations(): void {
    if (!this.searchTerm || this.searchTerm.trim() === '') {
      this.filteredConversations = [...this.conversations];
      return;
    }

    const searchTermLower = this.searchTerm.toLowerCase().trim();

    this.filteredConversations = this.conversations.filter(conv => {
      if (conv.title && conv.title.toLowerCase().includes(searchTermLower)) {
        return true;
      }

      if (conv.messages && conv.messages.length > 0) {
        return conv.messages.some(msg =>
          msg.content.toLowerCase().includes(searchTermLower)
        );
      }

      return false;
    });
  }

  clearSearch(): void {
    this.searchTerm = '';
    this.searchTermChanged.next('');
  }

  // Helper methods for displaying conversation data
  getFirstMessage(conversation: HistoryResponse): string {
    if (conversation.messages && conversation.messages.length > 0 &&
      conversation.messages[0].role === 'user') {
      return conversation.messages[0].content || 'New Conversation';
    }
    return 'New Conversation';
  }

  getLastMessagePreview(conversation: HistoryResponse): string {
    if (!conversation.messages || conversation.messages.length === 0) {
      return 'Empty conversation';
    }

    const lastMessage = conversation.messages[conversation.messages.length - 1];
    const role = lastMessage.role === 'user' ? 'You: ' :
      lastMessage.role === 'assistant' ? 'Assistant: ' : '';

    return role + lastMessage.content.substring(0, 40) +
      (lastMessage.content.length > 40 ? '...' : '');
  }

  getTimestamp(conversation: HistoryResponse): string {
    if (conversation.updatedAt) {
      return conversation.updatedAt;
    } else if (conversation.messages && conversation.messages.length > 0) {
      return conversation.messages[conversation.messages.length - 1].timestamp;
    } else if (conversation.createdAt) {
      return conversation.createdAt;
    }
    return '';
  }

  ngOnDestroy(): void {
    if (this.routerSub) this.routerSub.unsubscribe();
    if (this.conversationLoadSub) this.conversationLoadSub.unsubscribe();
    if (this.deleteConversationSub) this.deleteConversationSub.unsubscribe();
    if (this.deleteAllSub) this.deleteAllSub.unsubscribe();
    if (this.refreshEventSub) this.refreshEventSub.unsubscribe();
    if (this.searchSub) this.searchSub.unsubscribe();

    if (this.autoRefreshInterval) {
      clearInterval(this.autoRefreshInterval);
    }
  }
}
