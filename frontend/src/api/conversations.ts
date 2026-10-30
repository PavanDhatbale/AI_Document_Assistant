import { apiClient } from './client';
import {
  AddDocumentsRequest,
  ChatAnswerResponse,
  ConversationDetailResponse,
  ConversationResponse,
  CreateConversationRequest,
  MessageResponse,
  SendMessageRequest,
} from '../types/conversation';
import { DocumentResponse } from '../types/document';

export const conversationsApi = {
  async listConversations(): Promise<ConversationResponse[]> {
    const response = await apiClient.get<ConversationResponse[]>('/conversations');
    return response.data;
  },

  async getConversation(id: string): Promise<ConversationDetailResponse> {
    const response = await apiClient.get<ConversationDetailResponse>(`/conversations/${id}`);
    return response.data;
  },

  async createConversation(data?: CreateConversationRequest): Promise<ConversationResponse> {
    const response = await apiClient.post<ConversationResponse>('/conversations', data || {});
    return response.data;
  },

  async deleteConversation(id: string): Promise<{ message: string; id: string }> {
    const response = await apiClient.delete<{ message: string; id: string }>(`/conversations/${id}`);
    return response.data;
  },

  async sendMessage(id: string, content: string): Promise<ChatAnswerResponse> {
    const payload: SendMessageRequest = { content };
    const response = await apiClient.post<ChatAnswerResponse>(`/conversations/${id}/messages`, payload);
    return response.data;
  },

  async getMessages(id: string): Promise<MessageResponse[]> {
    const response = await apiClient.get<MessageResponse[]>(`/conversations/${id}/messages`);
    return response.data;
  },

  async addDocumentsToConversation(id: string, documentIds: string[]): Promise<DocumentResponse[]> {
    const payload: AddDocumentsRequest = { documentIds };
    const response = await apiClient.post<DocumentResponse[]>(`/conversations/${id}/documents`, payload);
    return response.data;
  },

  async removeDocumentFromConversation(
    id: string,
    documentId: string
  ): Promise<{ message: string; documentId: string }> {
    const response = await apiClient.delete<{ message: string; documentId: string }>(
      `/conversations/${id}/documents/${documentId}`
    );
    return response.data;
  },

  async getConversationDocuments(id: string): Promise<DocumentResponse[]> {
    const response = await apiClient.get<DocumentResponse[]>(`/conversations/${id}/documents`);
    return response.data;
  },
};
