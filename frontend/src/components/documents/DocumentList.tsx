import React from 'react';
import { DocumentResponse } from '../../types/document';
import { DocumentCard } from './DocumentCard';
import { EmptyState } from '../common/EmptyState';
import { FileUp, FileText } from 'lucide-react';

interface DocumentListProps {
  documents: DocumentResponse[];
  isLoading: boolean;
  selectedIds?: string[];
  onToggleSelect?: (id: string) => void;
  onViewChunks?: (document: DocumentResponse) => void;
  onProcess: (id: string) => Promise<void>;
  onDelete: (id: string) => void;
  onRetryDelete: (id: string) => Promise<void>;
  onUploadClick: () => void;
  emptyTitle?: string;
  emptyDescription?: string;
}

export const DocumentList: React.FC<DocumentListProps> = ({
  documents,
  isLoading,
  selectedIds = [],
  onToggleSelect,
  onViewChunks,
  onProcess,
  onDelete,
  onRetryDelete,
  onUploadClick,
  emptyTitle = 'No documents uploaded yet',
  emptyDescription = 'Upload research papers, quarterly reports, contracts, or manuals to begin grounded RAG conversations.',
}) => {
  if (isLoading) {
    return (
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 animate-pulse">
        {[1, 2, 3].map((n) => (
          <div key={n} className="h-52 bg-slate-200/60 rounded-xl" />
        ))}
      </div>
    );
  }

  if (documents.length === 0) {
    return (
      <EmptyState
        icon={<FileText className="w-6 h-6" />}
        title={emptyTitle}
        description={emptyDescription}
        actionText="Upload First Document"
        actionIcon={<FileUp className="w-4 h-4" />}
        onAction={onUploadClick}
      />
    );
  }

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
      {documents.map((doc) => (
        <DocumentCard
          key={doc.id}
          document={doc}
          isSelected={selectedIds.includes(doc.id)}
          onToggleSelect={onToggleSelect}
          onViewChunks={onViewChunks}
          onProcess={onProcess}
          onDelete={onDelete}
          onRetryDelete={onRetryDelete}
        />
      ))}
    </div>
  );
};
