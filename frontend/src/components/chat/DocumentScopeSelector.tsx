import React, { useState } from 'react';
import { DocumentResponse } from '../../types/document';
import { Modal } from '../common/Modal';
import { Button } from '../common/Button';
import { Layers, Check, Plus, X, Globe } from 'lucide-react';

interface DocumentScopeSelectorProps {
  conversationId: string;
  scopedDocuments: DocumentResponse[];
  allUserDocuments: DocumentResponse[];
  onAddDocuments: (docIds: string[]) => Promise<void>;
  onRemoveDocument: (docId: string) => Promise<void>;
}

export const DocumentScopeSelector: React.FC<DocumentScopeSelectorProps> = ({
  scopedDocuments,
  allUserDocuments,
  onAddDocuments,
  onRemoveDocument,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const [selectedDocIds, setSelectedDocIds] = useState<string[]>([]);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const readyDocuments = allUserDocuments.filter((d) => d.status === 'READY');
  const scopedDocIds = new Set(scopedDocuments.map((d) => d.id));
  const unattachedReadyDocs = readyDocuments.filter((d) => !scopedDocIds.has(d.id));

  const toggleSelectDoc = (id: string) => {
    setSelectedDocIds((prev) =>
      prev.includes(id) ? prev.filter((d) => d !== id) : [...prev, id]
    );
  };

  const handleAttach = async () => {
    if (selectedDocIds.length === 0) return;
    setIsSubmitting(true);
    try {
      await onAddDocuments(selectedDocIds);
      setSelectedDocIds([]);
      setIsOpen(false);
    } finally {
      setIsSubmitting(false);
    }
  };

  const isDefaultScope = scopedDocuments.length === 0;

  return (
    <>
      <div className="flex items-center justify-between gap-3 px-4 py-2 bg-slate-50 border-b border-slate-200/70 text-xs">
        <div className="flex items-center gap-2 min-w-0">
          <Layers className="w-4 h-4 text-indigo-600 shrink-0" />
          <span className="font-semibold text-slate-700">Retrieval Scope:</span>
          {isDefaultScope ? (
            <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full bg-slate-200/80 text-slate-700 font-medium">
              <Globe className="w-3 h-3 text-slate-500" />
              All READY Documents ({readyDocuments.length})
            </span>
          ) : (
            <div className="flex items-center gap-1.5 flex-wrap">
              <span className="px-2 py-0.5 rounded-full bg-indigo-50 text-indigo-700 font-medium border border-indigo-200">
                {scopedDocuments.length} Scoped Document{scopedDocuments.length > 1 ? 's' : ''}
              </span>
              {scopedDocuments.map((doc) => (
                <span
                  key={doc.id}
                  className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-white border border-slate-200 text-slate-700"
                  title={doc.originalFileName}
                >
                  <span className="max-w-[120px] truncate">{doc.originalFileName}</span>
                  <button
                    onClick={() => onRemoveDocument(doc.id)}
                    className="text-slate-400 hover:text-rose-600"
                    aria-label={`Remove ${doc.originalFileName} from scope`}
                  >
                    <X className="w-3 h-3" />
                  </button>
                </span>
              ))}
            </div>
          )}
        </div>

        <button
          onClick={() => setIsOpen(true)}
          className="shrink-0 text-indigo-600 hover:text-indigo-800 font-medium hover:underline focus:outline-none flex items-center gap-1"
        >
          <Plus className="w-3.5 h-3.5" />
          <span>{isDefaultScope ? 'Limit Scope' : 'Add Docs'}</span>
        </button>
      </div>

      {/* Scope Management Modal */}
      <Modal
        isOpen={isOpen}
        onClose={() => setIsOpen(false)}
        title="Manage Conversation Document Scope"
        description="Select specific READY documents to search. If no documents are selected, the assistant defaults to searching across all your READY documents."
        maxWidth="md"
      >
        <div className="space-y-4">
          {unattachedReadyDocs.length === 0 ? (
            <p className="text-xs text-slate-500 py-4 text-center">
              All of your ready documents are already attached to this conversation, or no other READY documents exist.
            </p>
          ) : (
            <div className="max-h-60 overflow-y-auto space-y-2 pr-1">
              {unattachedReadyDocs.map((doc) => {
                const isSelected = selectedDocIds.includes(doc.id);
                return (
                  <div
                    key={doc.id}
                    onClick={() => toggleSelectDoc(doc.id)}
                    className={`flex items-center justify-between p-3 rounded-lg border cursor-pointer transition ${
                      isSelected
                        ? 'border-indigo-600 bg-indigo-50/50'
                        : 'border-slate-200 hover:border-slate-300 bg-white'
                    }`}
                  >
                    <div className="min-w-0 pr-3">
                      <p className="text-xs font-semibold text-slate-800 truncate">
                        {doc.originalFileName}
                      </p>
                      <p className="text-[11px] text-slate-500">
                        {doc.totalPages || 0} pages • {doc.totalChunks || 0} chunks
                      </p>
                    </div>

                    <div
                      className={`w-5 h-5 rounded-md flex items-center justify-center shrink-0 border ${
                        isSelected
                          ? 'bg-indigo-600 border-indigo-600 text-white'
                          : 'border-slate-300 bg-white'
                      }`}
                    >
                      {isSelected && <Check className="w-3.5 h-3.5" />}
                    </div>
                  </div>
                );
              })}
            </div>
          )}

          <div className="flex justify-end gap-3 pt-3 border-t border-slate-100">
            <Button variant="ghost" onClick={() => setIsOpen(false)}>
              Cancel
            </Button>
            <Button
              onClick={handleAttach}
              disabled={selectedDocIds.length === 0 || isSubmitting}
              isLoading={isSubmitting}
            >
              Attach Selected ({selectedDocIds.length})
            </Button>
          </div>
        </div>
      </Modal>
    </>
  );
};
