import { apiClient } from './client';
import { BulkDeleteResponse, DocumentChunkResponse, DocumentResponse } from '../types/document';

export interface ListDocumentsParams {
  search?: string;
  status?: string;
  sortBy?: string;
  sortDirection?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

export const documentsApi = {
  async listDocuments(params?: ListDocumentsParams): Promise<DocumentResponse[]> {
    const response = await apiClient.get<DocumentResponse[]>('/documents', { params });
    return response.data;
  },

  async getDocument(id: string): Promise<DocumentResponse> {
    const response = await apiClient.get<DocumentResponse>(`/documents/${id}`);
    return response.data;
  },

  async uploadDocument(
    file: File,
    onUploadProgress?: (progressEvent: { loaded: number; total?: number }) => void
  ): Promise<DocumentResponse> {
    const formData = new FormData();
    formData.append('file', file);

    const response = await apiClient.post<DocumentResponse>('/documents', formData, {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
      onUploadProgress: (progressEvent) => {
        if (onUploadProgress) {
          onUploadProgress({
            loaded: progressEvent.loaded,
            total: progressEvent.total,
          });
        }
      },
    });
    return response.data;
  },

  async processDocument(id: string): Promise<DocumentResponse> {
    const response = await apiClient.post<DocumentResponse>(`/documents/${id}/process`);
    return response.data;
  },

  async deleteDocument(id: string): Promise<{ message: string; id: string }> {
    const response = await apiClient.delete<{ message: string; id: string }>(`/documents/${id}`);
    return response.data;
  },

  async bulkDelete(documentIds: string[]): Promise<BulkDeleteResponse> {
    const response = await apiClient.post<BulkDeleteResponse>('/documents/bulk-delete', { documentIds });
    return response.data;
  },

  async retryDeleteDocument(id: string): Promise<{ message: string; id: string }> {
    const response = await apiClient.post<{ message: string; id: string }>(`/documents/${id}/retry-delete`);
    return response.data;
  },

  async getDocumentChunks(id: string): Promise<DocumentChunkResponse[]> {
    const response = await apiClient.get<DocumentChunkResponse[]>(`/documents/${id}/chunks`);
    return response.data;
  },
};
