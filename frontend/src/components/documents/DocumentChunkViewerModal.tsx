import React, { useState, useEffect } from 'react';
import { Modal } from '../common/Modal';
import { Button } from '../common/Button';
import { LoadingSpinner } from '../common/LoadingSpinner';
import { DocumentChunkResponse, DocumentResponse } from '../../types/document';
import { documentsApi } from '../../api/documents';
import { Search, Copy, Check, FileText, Hash, Layers } from 'lucide-react';

interface DocumentChunkViewerModalProps {
  document: DocumentResponse | null;
  isOpen: boolean;
  onClose: () => void;
}

export const DocumentChunkViewerModal: React.FC<DocumentChunkViewerModalProps> = ({
  document,
  isOpen,
  onClose,
}) => {
  const [chunks, setChunks] = useState<DocumentChunkResponse[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [copiedIndex, setCopiedIndex] = useState<number | null>(null);

  useEffect(() => {
    if (!isOpen || !document) {
      setChunks([]);
      setError(null);
      setSearchQuery('');
      return;
    }

    let isMounted = true;
    setIsLoading(true);
    setError(null);

    documentsApi
      .getDocumentChunks(document.id)
      .then((data) => {
        if (isMounted) {
          setChunks(data);
          setIsLoading(false);
        }
      })
      .catch((err: unknown) => {
        if (isMounted) {
          const apiErr = err as { message?: string };
          setError(apiErr.message || 'Failed to retrieve document chunks.');
          setIsLoading(false);
        }
      });

    return () => {
      isMounted = false;
    };
  }, [isOpen, document]);

  const handleCopy = (text: string, index: number) => {
    navigator.clipboard.writeText(text);
    setCopiedIndex(index);
    setTimeout(() => {
      setCopiedIndex(null);
    }, 2000);
  };

  const filteredChunks = chunks.filter((c) => {
    const text = (c.chunkText || c.textSnippet || '').toLowerCase();
    const query = searchQuery.trim().toLowerCase();
    if (!query) return true;
    return text.includes(query) || `page ${c.pageNumber}`.includes(query) || `chunk ${c.chunkIndex}`.includes(query);
  });

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="Extracted Document Chunks"
      description={document ? `Inspecting ${chunks.length} chunks extracted from "${document.originalFileName}"` : ''}
      maxWidth="xl"
    >
      <div className="space-y-4">
        {/* Document Quick Info & Search Toolbar */}
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 p-3 bg-slate-50 rounded-xl border border-slate-200/80">
          <div className="flex items-center gap-3 text-xs text-slate-600">
            <span className="flex items-center gap-1 font-medium text-slate-800">
              <Layers className="w-3.5 h-3.5 text-indigo-600" />
              {chunks.length} Total Chunks
            </span>
            <span className="text-slate-300">•</span>
            <span className="flex items-center gap-1 font-medium text-slate-800">
              <FileText className="w-3.5 h-3.5 text-slate-500" />
              {document?.totalPages || 0} Pages
            </span>
          </div>

          <div className="relative flex-1 sm:max-w-xs">
            <Search className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2" />
            <input
              type="text"
              placeholder="Search in chunks..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full pl-9 pr-3 py-1.5 text-xs rounded-lg border border-slate-200 bg-white placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
              data-testid="chunk-search-input"
            />
          </div>
        </div>

        {/* Content Area */}
        <div className="max-h-[60vh] overflow-y-auto space-y-3 pr-1">
          {isLoading ? (
            <div className="py-12 flex flex-col items-center justify-center gap-2">
              <LoadingSpinner size="md" />
              <p className="text-xs text-slate-500 font-medium">Loading extracted chunks...</p>
            </div>
          ) : error ? (
            <div className="p-4 bg-rose-50 border border-rose-200 rounded-xl text-center text-xs text-rose-700">
              <p className="font-semibold">Error Loading Chunks</p>
              <p className="mt-1">{error}</p>
            </div>
          ) : filteredChunks.length === 0 ? (
            <div className="py-10 text-center text-slate-500 text-xs">
              {searchQuery ? 'No chunks match your search query.' : 'No chunks available for this document.'}
            </div>
          ) : (
            filteredChunks.map((chunk) => {
              const textContent = chunk.chunkText || chunk.textSnippet || '';
              return (
                <div
                  key={chunk.id || chunk.chunkIndex}
                  className="p-4 bg-white rounded-xl border border-slate-200/90 shadow-2xs space-y-2 hover:border-slate-300 transition"
                  data-testid={`chunk-item-${chunk.chunkIndex}`}
                >
                  <div className="flex items-center justify-between gap-2 border-b border-slate-100 pb-2">
                    <div className="flex items-center gap-2">
                      <span className="inline-flex items-center gap-0.5 px-2 py-0.5 rounded-md bg-indigo-50 text-indigo-700 font-semibold text-2xs">
                        <Hash className="w-3 h-3 text-indigo-500" />
                        Chunk {chunk.chunkIndex}
                      </span>
                      <span className="px-2 py-0.5 rounded-md bg-slate-100 text-slate-700 font-medium text-2xs">
                        Page {chunk.pageNumber}
                      </span>
                      <span className="px-2 py-0.5 rounded-md bg-slate-100 text-slate-600 font-medium text-2xs">
                        {chunk.tokenCount} tokens
                      </span>
                    </div>

                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => handleCopy(textContent, chunk.chunkIndex)}
                      className="text-slate-400 hover:text-slate-700 h-6 px-2 text-2xs gap-1"
                      title="Copy chunk text"
                    >
                      {copiedIndex === chunk.chunkIndex ? (
                        <>
                          <Check className="w-3 h-3 text-emerald-600" />
                          <span className="text-emerald-600">Copied</span>
                        </>
                      ) : (
                        <>
                          <Copy className="w-3 h-3" />
                          <span>Copy</span>
                        </>
                      )}
                    </Button>
                  </div>

                  <p className="text-xs text-slate-700 leading-relaxed font-mono whitespace-pre-wrap bg-slate-50/70 p-3 rounded-lg border border-slate-100">
                    {textContent}
                  </p>
                </div>
              );
            })
          )}
        </div>

        {/* Footer */}
        <div className="flex justify-end pt-3 border-t border-slate-100">
          <Button variant="outline" size="sm" onClick={onClose}>
            Close
          </Button>
        </div>
      </div>
    </Modal>
  );
};
