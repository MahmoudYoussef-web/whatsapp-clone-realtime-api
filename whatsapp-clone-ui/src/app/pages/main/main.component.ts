import {Component, ElementRef, OnDestroy, OnInit, ViewChild} from '@angular/core';
import {ChatListComponent} from '../../components/chat-list/chat-list.component';
import {KeycloakService} from '../../utils/keycloak/keycloak.service';
import {ConversationResponse} from '../../services/models/conversation-response';
import {DatePipe} from '@angular/common';
import {MessageService} from '../../services/services/message.service';
import {ConversationService} from '../../services/services/conversation.service';
import {MessageResponse} from '../../services/models/message-response';
import {AttachmentResponse} from '../../services/models/attachment-response';
import {Stomp} from 'stompjs/lib/stomp.js';
import SockJS from 'sockjs-client';
import {FormsModule} from '@angular/forms';
import {Notification} from './models/notification';
import {PickerComponent} from '@ctrl/ngx-emoji-mart';
import {EmojiData} from '@ctrl/ngx-emoji-mart/ngx-emoji';

const DELETED_PLACEHOLDER = 'This message was deleted';

@Component({
  selector: 'app-main',
  imports: [
    ChatListComponent,
    DatePipe,
    FormsModule,
    PickerComponent
  ],
  templateUrl: './main.component.html',
  styleUrl: './main.component.scss'
})
export class MainComponent implements OnInit, OnDestroy {

  conversations: Array<ConversationResponse> = [];
  selectedConversation: ConversationResponse | null = null;
  chatMessages: Array<MessageResponse> = [];
  socketClient: any = null;
  messageContent: string = '';
  showEmojis = false;
  hasMore = false;
  nextCursor: number | undefined;
  loadingOlder = false;
  pendingReply: MessageResponse | null = null;
  editingMessage: MessageResponse | null = null;
  otherUserTyping = false;
  @ViewChild('scrollableDiv') scrollableDiv!: ElementRef<HTMLDivElement>;
  private notificationSubscription: any;
  private typingTimer: any = null;
  private typingTimeout: any = null;

  constructor(
    private conversationService: ConversationService,
    private messageService: MessageService,
    private keycloakService: KeycloakService,
  ) {
  }

  ngOnDestroy(): void {
    if (this.socketClient !== null) {
      this.socketClient.disconnect();
      this.notificationSubscription?.unsubscribe();
      this.socketClient = null;
    }
    clearTimeout(this.typingTimer);
    clearTimeout(this.typingTimeout);
  }

  ngOnInit(): void {
    this.initWebSocket();
    this.getAllConversations();
  }

  chatSelected(conversation: ConversationResponse) {
    this.selectedConversation = conversation;
    this.loadFirstPage(conversation.id as string);
    this.markAsRead(conversation);
    this.otherUserTyping = false;
  }

  isSelfMessage(message: MessageResponse): boolean {
    return message.senderId === this.keycloakService.userId;
  }

  firstAttachmentUrl(message: MessageResponse): string {
    return message.attachments && message.attachments.length > 0
      ? (message.attachments[0].url as string)
      : '';
  }

  replyTo(message: MessageResponse) {
    this.editingMessage = null;
    this.pendingReply = message;
  }

  cancelReply() {
    this.pendingReply = null;
  }

  startEdit(message: MessageResponse) {
    this.pendingReply = null;
    this.editingMessage = message;
    this.messageContent = message.content ?? '';
  }

  cancelEdit() {
    this.editingMessage = null;
    this.messageContent = '';
  }

  deleteMessage(message: MessageResponse, mode: 'me' | 'everyone') {
    if (!this.selectedConversation?.id) {
      return;
    }
    const conversation = this.selectedConversation;
    this.messageService.deleteMessage({
      'conversation-id': conversation.id as string,
      'message-id': message.id as number,
      mode: mode
    }).subscribe({
      next: () => {
        // The deleter receives no WS echo - mask locally.
        this.maskMessageLocally(message);
        this.maskLastMessagePreviewIfNeeded(message, conversation);
        if (this.pendingReply?.id === message.id) {
          this.pendingReply = null;
        }
      },
      error: () => console.error('Delete failed')
    });
  }

  sendMessage() {
    const content = this.messageContent?.trim();
    if (!content || !this.selectedConversation?.id) {
      return;
    }
    const conversation = this.selectedConversation;
    if (this.editingMessage) {
      this.saveEdit(content, conversation);
      return;
    }
    this.messageService.sendMessage({
      'conversation-id': conversation.id as string,
      body: {
        content: content,
        type: 'TEXT',
        replyToMessageId: this.pendingReply?.id
      }
    }).subscribe({
      next: (message) => {
        this.chatMessages.push(message);
        this.updateLastMessageInList(conversation, message);
        this.messageContent = '';
        this.showEmojis = false;
        this.pendingReply = null;
        this.scrollToBottom();
      },
      error: () => {
        this.messageContent = content;
      }
    });
    this.sendTyping(false);
  }

  private saveEdit(content: string, conversation: ConversationResponse) {
    const editing = this.editingMessage;
    if (!editing?.id) {
      return;
    }
    this.messageService.editMessage({
      'conversation-id': conversation.id as string,
      'message-id': editing.id,
      body: {content: content}
    }).subscribe({
      next: (updated) => {
        this.applyMessageUpdate(updated);
        this.editingMessage = null;
        this.messageContent = '';
      },
      error: () => {
        this.messageContent = content;
      }
    });
    this.sendTyping(false);
  }

  keyDown(event: KeyboardEvent) {
    if (event.key === 'Enter') {
      this.sendMessage();
    }
  }

  onTypingInput() {
    if (!this.selectedConversation?.id) {
      return;
    }
    // Throttle typing frames: at most one every 1.5s.
    if (!this.typingTimer) {
      this.sendTyping(true);
      this.typingTimer = setTimeout(() => this.typingTimer = null, 1500);
    }
  }

  onTypingStop() {
    this.sendTyping(false);
  }

  private sendTyping(typing: boolean) {
    if (this.socketClient?.connected && this.selectedConversation?.id) {
      this.socketClient.send('/app/typing', {}, JSON.stringify({
        conversationId: this.selectedConversation.id,
        typing: typing
      }));
    }
  }

  onSelectEmojis(emojiSelected: any) {
    const emoji: EmojiData = emojiSelected.emoji;
    this.messageContent += emoji.native;
  }

  uploadMedia(target: EventTarget | null) {
    const file = this.extractFileFromTarget(target);
    if (file !== null && this.selectedConversation?.id) {
      const conversation = this.selectedConversation;
      this.messageService.uploadAttachment({
        'conversation-id': conversation.id as string,
        body: {
          file: file
        }
      }).subscribe({
        next: (message) => {
          this.chatMessages.push(message);
          this.updateLastMessageInList(conversation, message);
          this.scrollToBottom();
        },
        error: () => {
          console.error('Upload failed');
        }
      });
    }
  }

  logout() {
    this.keycloakService.logout();
  }

  userProfile() {
    this.keycloakService.accountManagement();
  }

  onScroll(event: Event) {
    const target = event.target as HTMLDivElement;
    if (target.scrollTop < 50) {
      this.loadOlder();
    }
  }

  private markAsRead(conversation: ConversationResponse) {
    this.messageService.markMessagesAsRead({
      'conversation-id': conversation.id as string
    }).subscribe({
      next: () => {
        conversation.unreadCount = 0;
      }
    });
  }

  private getAllConversations() {
    this.conversationService.getConversations()
      .subscribe({
        next: (res) => {
          this.conversations = res;
        }
      });
  }

  private loadFirstPage(conversationId: string) {
    this.chatMessages = [];
    this.hasMore = false;
    this.nextCursor = undefined;
    this.loadingOlder = false;
    this.messageService.getMessages({
      'conversation-id': conversationId,
      limit: 30
    }).subscribe({
      next: (page) => {
        this.chatMessages = [...(page.messages ?? [])].sort((a, b) => (a.id ?? 0) - (b.id ?? 0));
        this.hasMore = page.hasMore ?? false;
        this.nextCursor = page.nextCursor;
        setTimeout(() => this.scrollToBottom(), 0);
      }
    });
  }

  private loadOlder() {
    if (this.loadingOlder || !this.hasMore || !this.selectedConversation?.id) {
      return;
    }
    this.loadingOlder = true;
    const conversationId = this.selectedConversation.id;
    const container = this.scrollableDiv?.nativeElement;
    const previousHeight = container ? container.scrollHeight : 0;
    this.messageService.getMessages({
      'conversation-id': conversationId,
      before: this.nextCursor,
      limit: 30
    }).subscribe({
      next: (page) => {
        if (this.selectedConversation?.id !== conversationId) {
          return;
        }
        const older = [...(page.messages ?? [])].sort((a, b) => (a.id ?? 0) - (b.id ?? 0));
        this.chatMessages = [...older, ...this.chatMessages];
        this.hasMore = page.hasMore ?? false;
        this.nextCursor = page.nextCursor;
        if (container) {
          container.scrollTop = container.scrollHeight - previousHeight;
        }
        this.loadingOlder = false;
      },
      error: () => {
        this.loadingOlder = false;
      }
    });
  }

  private initWebSocket() {
    if (this.keycloakService.keycloak.tokenParsed?.sub) {
      let ws = new SockJS('http://localhost:8080/ws');
      this.socketClient = Stomp.over(ws);
      const subUrl = `/user/${this.keycloakService.keycloak.tokenParsed?.sub}/chat`;
      this.socketClient.connect({'Authorization': 'Bearer ' + this.keycloakService.keycloak.token},
        () => {
          this.notificationSubscription = this.socketClient.subscribe(subUrl,
            (message: any) => {
              const notification: Notification = JSON.parse(message.body);
              this.handleNotification(notification);
            },
            () => console.error('Error while connecting to webSocket')
          );
        }
      );
    }
  }

  private handleNotification(notification: Notification) {
    if (!notification) return;
    switch (notification.type) {
      case 'MESSAGE':
        this.handleNewMessage(notification);
        break;
      case 'DELIVERED':
        this.handleDelivered(notification);
        break;
      case 'READ':
        this.handleReadReceipt(notification);
        break;
      case 'TYPING':
        this.handleTyping(notification);
        break;
      case 'MESSAGE_EDITED':
        this.handleMessageEdited(notification);
        break;
      case 'MESSAGE_DELETED':
        this.handleMessageDeleted(notification);
        break;
    }
  }

  private handleNewMessage(notification: Notification) {
    const message = notification.message;
    if (!message) return;
    if (this.selectedConversation && this.selectedConversation.id === notification.conversationId) {
      this.chatMessages.push(message);
      this.updateLastMessageInList(this.selectedConversation, message);
      this.markAsRead(this.selectedConversation);
      this.scrollToBottom();
      // Delivery acknowledgement: SENT -> DELIVERED on the sender's side.
      this.acknowledgeDelivered(message.id);
    } else {
      const destConversation = this.conversations.find(c => c.id === notification.conversationId);
      if (destConversation) {
        this.updateLastMessageInList(destConversation, message);
        destConversation.unreadCount = (destConversation.unreadCount ?? 0) + 1;
        this.moveToTop(destConversation);
      } else {
        this.getAllConversations();
      }
    }
  }

  private handleDelivered(notification: Notification) {
    if (notification.messageId) {
      this.updateMessageStatus(notification.messageId, 'DELIVERED');
    } else {
      // Offline delivery: every still-SENT message of mine in this conversation
      // was delivered when the other side loaded the history.
      if (this.selectedConversation && this.selectedConversation.id === notification.conversationId) {
        this.chatMessages.forEach(m => {
          if (this.isSelfMessage(m) && m.status === 'SENT') {
            m.status = 'DELIVERED';
          }
        });
      }
      const destConversation = this.conversations.find(c => c.id === notification.conversationId);
      if (destConversation && destConversation.lastMessageStatus === 'SENT') {
        destConversation.lastMessageStatus = 'DELIVERED';
      }
    }
  }

  private handleReadReceipt(notification: Notification) {
    if (this.selectedConversation && this.selectedConversation.id === notification.conversationId) {
      this.chatMessages.forEach(m => {
        if (this.isSelfMessage(m) && m.status !== 'READ') {
          m.status = 'READ';
        }
      });
    }
    const destConversation = this.conversations.find(c => c.id === notification.conversationId);
    if (destConversation && destConversation.lastMessageStatus !== 'READ') {
      destConversation.lastMessageStatus = 'READ';
    }
  }

  private handleTyping(notification: Notification) {
    if (this.selectedConversation && this.selectedConversation.id === notification.conversationId) {
      this.otherUserTyping = notification.typing === true;
      clearTimeout(this.typingTimeout);
      if (this.otherUserTyping) {
        this.typingTimeout = setTimeout(() => this.otherUserTyping = false, 3000);
      }
    }
  }

  private handleMessageEdited(notification: Notification) {
    const message = notification.message;
    if (!message) return;
    this.applyMessageUpdate(message);
  }

  private handleMessageDeleted(notification: Notification) {
    if (!notification.messageId) return;
    const message = this.chatMessages.find(m => m.id === notification.messageId);
    if (message) {
      this.maskMessageLocally(message);
      if (this.selectedConversation) {
        this.maskLastMessagePreviewIfNeeded(message, this.selectedConversation);
      }
    }
    if (this.pendingReply?.id === notification.messageId) {
      this.pendingReply = null;
    }
  }

  private applyMessageUpdate(updated: MessageResponse) {
    const index = this.chatMessages.findIndex(m => m.id === updated.id);
    if (index >= 0) {
      const previous = this.chatMessages[index];
      const isLast = this.selectedConversation?.lastMessageId === updated.id;
      this.chatMessages[index] = {...previous, ...updated};
      if (isLast && this.selectedConversation) {
        this.selectedConversation.lastMessage = updated.content || 'Attachment';
      }
    }
  }

  private maskMessageLocally(message: MessageResponse) {
    message.deleted = true;
    message.content = DELETED_PLACEHOLDER;
    message.attachments = [];
  }

  private maskLastMessagePreviewIfNeeded(message: MessageResponse, conversation: ConversationResponse) {
    if (conversation.lastMessageId === message.id) {
      conversation.lastMessage = DELETED_PLACEHOLDER;
    }
  }

  private acknowledgeDelivered(messageId?: number) {
    if (this.socketClient?.connected && messageId) {
      this.socketClient.send('/app/message-ack', {}, JSON.stringify({messageId: messageId}));
    }
  }

  private updateMessageStatus(messageId: number, status: 'SENT' | 'DELIVERED' | 'READ') {
    const message = this.chatMessages.find(m => m.id === messageId);
    if (message && message.status === 'SENT') {
      message.status = status;
    }
    if (this.selectedConversation && this.selectedConversation.lastMessageId === messageId) {
      this.selectedConversation.lastMessageStatus = status;
    }
  }

  private updateLastMessageInList(conversation: ConversationResponse, message: MessageResponse) {
    conversation.lastMessageId = message.id;
    conversation.lastMessage = message.content || 'Attachment';
    conversation.lastMessageType = message.type;
    conversation.lastMessageStatus = message.status;
    conversation.lastMessageTime = message.createdAt;
    this.moveToTop(conversation);
  }

  private moveToTop(conversation: ConversationResponse) {
    const index = this.conversations.findIndex(c => c.id === conversation.id);
    if (index > 0) {
      this.conversations.splice(index, 1);
      this.conversations.unshift(conversation);
    }
  }

  private scrollToBottom() {
    if (this.scrollableDiv) {
      const div = this.scrollableDiv.nativeElement;
      div.scrollTop = div.scrollHeight;
    }
  }

  private extractFileFromTarget(target: EventTarget | null): File | null {
    const htmlInputTarget = target as HTMLInputElement;
    if (target === null || htmlInputTarget.files === null) {
      return null;
    }
    return htmlInputTarget.files[0];
  }

  attachmentLabel(attachment: AttachmentResponse | null | undefined): string {
    return attachment?.mimeType?.includes('pdf') ? 'PDF' : (attachment?.mimeType?.split('/')[1] ?? 'File');
  }
}
