import React from 'react';
import { DocumentStatus } from '../../types/document';
import { Clock, Loader2, CheckCircle2, AlertOctagon, Trash2 } from 'lucide-react';

interface StatusBadgeProps {
  status: DocumentStatus;
  errorMessage?: string | null;
}

export const StatusBadge: React.FC<StatusBadgeProps> = ({ status, errorMessage }) => {
  switch (status) {
    case 'PENDING':
      return (
        <span
          className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-amber-50 text-amber-700 border border-amber-200"
          title="Document uploaded; waiting for text extraction and vector embedding"
        >
          <Clock className="w-3.5 h-3.5" />
          Pending
        </span>
      );
    case 'PROCESSING':
      return (
        <span
          className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-indigo-50 text-indigo-700 border border-indigo-200"
          title="Extracting text, chunking, and generating vector embeddings"
        >
          <Loader2 className="w-3.5 h-3.5 animate-spin" />
          Processing
        </span>
      );
    case 'READY':
      return (
        <span
          className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-emerald-50 text-emerald-700 border border-emerald-200"
          title="Ready for citation-grounded RAG conversations"
        >
          <CheckCircle2 className="w-3.5 h-3.5" />
          Ready
        </span>
      );
    case 'FAILED':
      return (
        <span
          className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-rose-50 text-rose-700 border border-rose-200"
          title={errorMessage || 'Document processing failed'}
        >
          <AlertOctagon className="w-3.5 h-3.5" />
          Failed
        </span>
      );
    case 'DELETING':
      return (
        <span
          className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-slate-100 text-slate-700 border border-slate-200"
          title="Purging vectors and disk files"
        >
          <Loader2 className="w-3.5 h-3.5 animate-spin" />
          Deleting
        </span>
      );
    case 'DELETION_FAILED':
      return (
        <span
          className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-rose-100 text-rose-800 border border-rose-300"
          title={errorMessage || 'Deletion failed. Retry deletion to clean up storage.'}
        >
          <Trash2 className="w-3.5 h-3.5" />
          Deletion Failed
        </span>
      );
    default:
      return (
        <span className="inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium bg-slate-100 text-slate-600">
          {status}
        </span>
      );
  }
};
