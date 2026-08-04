import {MessageResponse} from '../../../services/models/message-response';

export interface Notification {
  type: 'MESSAGE' | 'DELIVERED' | 'READ' | 'TYPING' | 'MESSAGE_EDITED' | 'MESSAGE_DELETED';
  conversationId?: string;
  senderId?: string;
  receiverId?: string;
  messageId?: number;
  message?: MessageResponse;
  typing?: boolean;
}
