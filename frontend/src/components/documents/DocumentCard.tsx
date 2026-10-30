import React, { useState } from 'react';
import { DocumentResponse } from '../../types/document';
import { StatusBadge } from './StatusBadge';
import { Button } from '../common/Button';
import { FileText, Play, Trash2, RotateCw, Layers } from 'lucide-react';

interface DocumentCardProps {
  document: DocumentResponse;
  isSelected?: boolean;
  onToggleSelect?: (id: string) => void;
  onViewChunks?: (document: DocumentResponse) => void;
  onProcess: (id: string) => Promise<void>;
  onDelete: (id: string) => void;
  onRetryDelete: (id: string) => Promise<void>;
}

export const DocumentCard: React.FC<DocumentCardProps> = ({
  document,
  isSelected = false,
  onToggleSelect,
  onViewChunks,
  onProcess,
  onDelete,
  onRetryDelete,
}) => {
  const [isProcessing, setIsProcessing] = useState(false);
  const [isRetryingDelete, setIsRetryingDelete] = useState(false);

  const formatFileSize = (bytes: number): string => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
  };

  const formatDate = (isoString: string): string => {
    try {
      const date = new Date(isoString);
      return date.toLocaleDateString(undefined, {
        month: 'short',
        day: 'numeric',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      });
    } catch {
      return isoString;
    }
  };

  const handleProcessClick = async () => {
    setIsProcessing(true);
    try {
      await onProcess(document.id);
    } finally {
      setIsProcessing(false);
    }
  };

  const handleRetryDeleteClick = async () => {
    setIsRetryingDelete(true);
    try {
      await onRetryDelete(document.id);
    } finally {
      setIsRetryingDelete(false);
    }
  };

  return (
    <div
      className={`p-5 bg-white rounded-xl border transition flex flex-col justify-between gap-4 relative ${
        isSelected
          ? 'border-indigo-500 ring-2 ring-indigo-500/20 shadow-sm bg-indigo-50/10'
          : 'border-slate-200/80 hover:border-slate-300 shadow-2xs hover:shadow-sm'
      }`}
      data-testid={`document-card-${document.id}`}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-start gap-3 min-w-0">
          {onToggleSelect && (
            <input
              type="checkbox"
              checked={isSelected}
              onChange={() => onToggleSelect(document.id)}
              className="w-4 h-4 mt-1 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500 cursor-pointer"
              aria-label={`Select ${document.originalFileName}`}
              data-testid={`select-doc-checkbox-${document.id}`}
            />
          )}

          <div className="w-10 h-10 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center text-indigo-600 shrink-0">
            <FileText className="w-5 h-5" />
          </div>
          <div className="min-w-0">
            <h4
              className="text-sm font-semibold text-slate-900 truncate hover:text-indigo-600 cursor-pointer"
              title={document.originalFileName}
              onClick={() => onViewChunks && document.status === 'READY' && onViewChunks(document)}
            >
              {document.originalFileName}
            </h4>
            <p className="text-xs text-slate-500 mt-0.5">
              {formatFileSize(document.fileSizeBytes)} • {formatDate(document.createdAt)}
            </p>
          </div>
        </div>

        <div className="shrink-0">
          <StatusBadge status={document.status} errorMessage={document.errorMessage} />
        </div>
      </div>

      {/* Metrics Row: Pages & Chunks */}
      <div className="grid grid-cols-2 gap-2 py-2 px-3 bg-slate-50 rounded-lg border border-slate-100 text-xs">
        <div>
          <span className="text-slate-400 block font-normal">Pages</span>
          <span className="font-semibold text-slate-700">
            {document.totalPages !== null ? document.totalPages : '—'}
          </span>
        </div>
        <div>
          <span className="text-slate-400 block font-normal">Indexed Chunks</span>
          <span className="font-semibold text-slate-700">
            {document.totalChunks !== null ? document.totalChunks : '—'}
          </span>
        </div>
      </div>

      {/* Error message if failed */}
      {document.errorMessage && document.status === 'FAILED' && (
        <p className="text-xs text-rose-600 bg-rose-50/50 p-2 rounded-md border border-rose-100">
          {document.errorMessage}
        </p>
      )}

      {/* Action Footer */}
      <div className="flex items-center justify-between pt-2 border-t border-slate-100 gap-2">
        <div className="flex items-center gap-2 flex-wrap">
          {(document.status === 'PENDING' || document.status === 'FAILED') && (
            <Button
              variant="outline"
              size="sm"
              onClick={handleProcessClick}
              isLoading={isProcessing}
              leftIcon={
                document.status === 'FAILED' ? (
                  <RotateCw className="w-3.5 h-3.5 text-amber-600" />
                ) : (
                  <Play className="w-3.5 h-3.5 text-indigo-600" />
                )
              }
              data-testid={document.status === 'FAILED' ? 'retry-process-btn' : 'process-btn'}
            >
              {document.status === 'FAILED' ? 'Retry Process' : 'Process Now'}
            </Button>
          )}

          {document.status === 'READY' && onViewChunks && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => onViewChunks(document)}
              leftIcon={<Layers className="w-3.5 h-3.5 text-indigo-600" />}
              data-testid={`view-chunks-btn-${document.id}`}
            >
              View Chunks
            </Button>
          )}

          {document.status === 'DELETION_FAILED' && (
            <Button
              variant="outline"
              size="sm"
              onClick={handleRetryDeleteClick}
              isLoading={isRetryingDelete}
              leftIcon={<RotateCw className="w-3.5 h-3.5 text-rose-600" />}
            >
              Retry Deletion
            </Button>
          )}
        </div>

        <Button
          variant="ghost"
          size="sm"
          onClick={() => onDelete(document.id)}
          disabled={document.status === 'DELETING'}
          className="text-slate-400 hover:text-rose-600 hover:bg-rose-50"
          aria-label={`Delete ${document.originalFileName}`}
        >
          <Trash2 className="w-4 h-4" />
        </Button>
      </div>
    </div>
  );
};
