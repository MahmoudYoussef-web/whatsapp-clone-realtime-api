import {Component, input, InputSignal, output} from '@angular/core';
import {ConversationService} from '../../services/services/conversation.service';
import {ConversationResponse} from '../../services/models/conversation-response';
import {DatePipe} from '@angular/common';
import {UserService} from '../../services/services/user.service';
import {UserResponse} from '../../services/models/user-response';
import {KeycloakService} from '../../utils/keycloak/keycloak.service';

@Component({
  selector: 'app-chat-list',
  templateUrl: './chat-list.component.html',
  imports: [
    DatePipe
  ],
  styleUrl: './chat-list.component.scss'
})
export class ChatListComponent {
  chats: InputSignal<ConversationResponse[]> = input<ConversationResponse[]>([]);
  searchNewContact = false;
  contacts: Array<UserResponse> = [];
  chatSelected = output<ConversationResponse>();

  constructor(
    private conversationService: ConversationService,
    private userService: UserService,
    private keycloakService: KeycloakService
  ) {
  }

  searchContact() {
    this.userService.getAllUsers()
      .subscribe({
        next: (users) => {
          this.contacts = users;
          this.searchNewContact = true;
        }
      });
  }

  selectContact(contact: UserResponse) {
    this.conversationService.createConversation({
      body: {
        participantId: contact.id as string
      }
    }).subscribe({
      next: (res) => {
        const conversation: ConversationResponse = {
          id: res.response,
          name: contact.firstName + ' ' + contact.lastName,
          otherUserId: contact.id,
          otherUserOnline: contact.online,
          otherUserLastSeen: contact.lastSeen
        };
        this.chats().unshift(conversation);
        this.searchNewContact = false;
        this.chatSelected.emit(conversation);
      }
    });
  }

  chatClicked(conversation: ConversationResponse) {
    this.chatSelected.emit(conversation);
  }

  wrapMessage(lastMessage: string | undefined): string {
    if (lastMessage && lastMessage.length <= 20) {
      return lastMessage;
    }
    return lastMessage?.substring(0, 17) + '...';
  }
}
