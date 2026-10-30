import { DocumentResponse } from './document';

export type MessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM';

export interface CitationResponse {
  id: string;
  documentId: string;
  documentName: string;
  pageNumber: number;
  chunkIndex: number;
  relevanceScore: number;
  excerptText: string;
  createdAt: string;
}

export interface MessageResponse {
  id: string;
  role: MessageRole;
  content: string;
  tokenCount: number;
  createdAt: string;
  citations: CitationResponse[];
}

export interface ConversationResponse {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
  documentCount: number;
  messageCount: number;
  documentIds: string[];
}

export interface ConversationDetailResponse {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
  documents: DocumentResponse[];
  messages: MessageResponse[];
}

export interface ChatAnswerResponse {
  userMessage: MessageResponse;
  assistantMessage: MessageResponse;
}

export interface CreateConversationRequest {
  title?: string;
  documentIds?: string[];
}

export interface AddDocumentsRequest {
  documentIds: string[];
}

export interface SendMessageRequest {
  content: string;
}
