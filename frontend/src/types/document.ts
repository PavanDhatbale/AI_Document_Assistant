export type DocumentStatus =
  | 'PENDING'
  | 'PROCESSING'
  | 'READY'
  | 'FAILED'
  | 'DELETING'
  | 'DELETION_FAILED';

export interface DocumentResponse {
  id: string;
  originalFileName: string;
  fileSizeBytes: number;
  totalPages: number | null;
  totalChunks: number | null;
  status: DocumentStatus;
  errorMessage?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface DocumentChunkResponse {
  id: string;
  chunkIndex: number;
  pageNumber: number;
  chunkText?: string;
  textSnippet?: string;
  tokenCount: number;
  createdAt?: string;
}

export interface BulkDeleteResponse {
  deletedCount: number;
  deletedIds: string[];
  failedIds: string[];
  message: string;
}

export type DocumentSortOption =
  | 'newest'
  | 'oldest'
  | 'name_asc'
  | 'name_desc'
  | 'size_desc'
  | 'size_asc';

export type DocumentStatusFilter = 'ALL' | 'READY' | 'PROCESSING' | 'FAILED';
