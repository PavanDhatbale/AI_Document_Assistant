import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { documentsApi } from '../api/documents';
import { DocumentResponse, DocumentSortOption, DocumentStatusFilter } from '../types/document';
import { DocumentList } from '../components/documents/DocumentList';
import { DocumentUploadModal } from '../components/documents/DocumentUploadModal';
import { DocumentChunkViewerModal } from '../components/documents/DocumentChunkViewerModal';
import { Button } from '../components/common/Button';
import { Modal } from '../components/common/Modal';
import { useToast } from '../context/ToastContext';
import {
  UploadCloud,
  RefreshCw,
  AlertTriangle,
  FileText,
  Search,
  X,
  ArrowUpDown,
  ChevronLeft,
  ChevronRight,
  Trash2,
  CheckSquare,
  Square,
} from 'lucide-react';

export const DocumentsPage: React.FC = () => {
  const [documents, setDocuments] = useState<DocumentResponse[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [isUploadModalOpen, setIsUploadModalOpen] = useState(false);

  // Search, Filter, Sort, Pagination
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<DocumentStatusFilter>('ALL');
  const [sortOption, setSortOption] = useState<DocumentSortOption>('newest');
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(6);

  // Bulk Selection & Delete
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [isBulkDeleteModalOpen, setIsBulkDeleteModalOpen] = useState(false);
  const [isBulkDeleting, setIsBulkDeleting] = useState(false);

  // Single Delete
  const [docToDelete, setDocToDelete] = useState<DocumentResponse | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);

  // Chunk Viewer Modal
  const [chunkViewerDoc, setChunkViewerDoc] = useState<DocumentResponse | null>(null);

  const { addToast } = useToast();
  const pollingRef = useRef<NodeJS.Timeout | null>(null);

  const fetchDocuments = useCallback(async (isManualRefresh = false) => {
    if (isManualRefresh) setIsRefreshing(true);
    try {
      const data = await documentsApi.listDocuments();
      setDocuments(data);
    } catch {
      addToast('Failed to load documents.', 'error');
    } finally {
      setIsLoading(false);
      if (isManualRefresh) setIsRefreshing(false);
    }
  }, [addToast]);

  useEffect(() => {
    fetchDocuments();
  }, [fetchDocuments]);

  // Gentle background status polling if any document is PROCESSING or DELETING
  useEffect(() => {
    const hasActiveTransfers = documents.some(
      (d) => d.status === 'PROCESSING' || d.status === 'DELETING'
    );

    if (hasActiveTransfers) {
      pollingRef.current = setInterval(() => {
        fetchDocuments();
      }, 4000);
    } else if (pollingRef.current) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }

    return () => {
      if (pollingRef.current) {
        clearInterval(pollingRef.current);
      }
    };
  }, [documents, fetchDocuments]);

  // Reset pagination when search or filter changes
  useEffect(() => {
    setCurrentPage(1);
  }, [searchQuery, statusFilter, sortOption]);

  // Calculate status counts for filter badges
  const statusCounts = useMemo(() => {
    const total = documents.length;
    const ready = documents.filter((d) => d.status === 'READY').length;
    const processing = documents.filter((d) => d.status === 'PROCESSING' || d.status === 'PENDING').length;
    const failed = documents.filter((d) => d.status === 'FAILED' || d.status === 'DELETION_FAILED').length;
    return { ALL: total, READY: ready, PROCESSING: processing, FAILED: failed };
  }, [documents]);

  // Filtered and Sorted Documents
  const filteredAndSortedDocuments = useMemo(() => {
    let result = [...documents];

    // 1. Search Query
    if (searchQuery.trim()) {
      const q = searchQuery.trim().toLowerCase();
      result = result.filter((d) => d.originalFileName.toLowerCase().includes(q));
    }

    // 2. Status Filter
    if (statusFilter !== 'ALL') {
      if (statusFilter === 'READY') {
        result = result.filter((d) => d.status === 'READY');
      } else if (statusFilter === 'PROCESSING') {
        result = result.filter((d) => d.status === 'PROCESSING' || d.status === 'PENDING');
      } else if (statusFilter === 'FAILED') {
        result = result.filter((d) => d.status === 'FAILED' || d.status === 'DELETION_FAILED');
      }
    }

    // 3. Sorting
    result.sort((a, b) => {
      switch (sortOption) {
        case 'newest':
          return new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime();
        case 'oldest':
          return new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime();
        case 'name_asc':
          return a.originalFileName.localeCompare(b.originalFileName);
        case 'name_desc':
          return b.originalFileName.localeCompare(a.originalFileName);
        case 'size_desc':
          return b.fileSizeBytes - a.fileSizeBytes;
        case 'size_asc':
          return a.fileSizeBytes - b.fileSizeBytes;
        default:
          return 0;
      }
    });

    return result;
  }, [documents, searchQuery, statusFilter, sortOption]);

  // Pagination calculations
  const totalItems = filteredAndSortedDocuments.length;
  const totalPages = Math.max(1, Math.ceil(totalItems / pageSize));
  const paginatedDocuments = useMemo(() => {
    const startIdx = (currentPage - 1) * pageSize;
    return filteredAndSortedDocuments.slice(startIdx, startIdx + pageSize);
  }, [filteredAndSortedDocuments, currentPage, pageSize]);

  // Selection handlers
  const handleToggleSelect = (id: string) => {
    setSelectedIds((prev) =>
      prev.includes(id) ? prev.filter((item) => item !== id) : [...prev, id]
    );
  };

  const handleSelectAllVisible = () => {
    const visibleIds = paginatedDocuments.map((d) => d.id);
    const allVisibleSelected = visibleIds.every((id) => selectedIds.includes(id));

    if (allVisibleSelected) {
      setSelectedIds((prev) => prev.filter((id) => !visibleIds.includes(id)));
    } else {
      setSelectedIds((prev) => Array.from(new Set([...prev, ...visibleIds])));
    }
  };

  const handleClearSelection = () => {
    setSelectedIds([]);
  };

  const areAllVisibleSelected =
    paginatedDocuments.length > 0 &&
    paginatedDocuments.every((d) => selectedIds.includes(d.id));

  // Process / Retry Processing
  const handleProcess = async (id: string) => {
    try {
      const updated = await documentsApi.processDocument(id);
      addToast(`Processing started for "${updated.originalFileName}".`, 'info');
      setDocuments((prev) => prev.map((d) => (d.id === id ? updated : d)));
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      addToast(apiErr.message || 'Failed to trigger document processing.', 'error');
    }
  };

  // Retry Deletion
  const handleRetryDelete = async (id: string) => {
    try {
      await documentsApi.retryDeleteDocument(id);
      addToast('Deletion retried successfully.', 'success');
      setDocuments((prev) => prev.filter((d) => d.id !== id));
      setSelectedIds((prev) => prev.filter((item) => item !== id));
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      addToast(apiErr.message || 'Retry deletion failed.', 'error');
    }
  };

  // Single Delete
  const handleDeleteConfirm = async () => {
    if (!docToDelete) return;
    setIsDeleting(true);
    try {
      await documentsApi.deleteDocument(docToDelete.id);
      addToast(`Document "${docToDelete.originalFileName}" deleted successfully.`, 'success');
      setDocuments((prev) => prev.filter((d) => d.id !== docToDelete.id));
      setSelectedIds((prev) => prev.filter((id) => id !== docToDelete.id));
      setDocToDelete(null);
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      addToast(apiErr.message || 'Failed to delete document.', 'error');
    } finally {
      setIsDeleting(false);
    }
  };

  // Bulk Delete
  const handleBulkDeleteConfirm = async () => {
    if (selectedIds.length === 0) return;
    setIsBulkDeleting(true);
    try {
      const response = await documentsApi.bulkDelete(selectedIds);
      addToast(`Deleted ${response.deletedCount} documents successfully.`, 'success');
      setDocuments((prev) => prev.filter((d) => !response.deletedIds.includes(d.id)));
      setSelectedIds([]);
      setIsBulkDeleteModalOpen(false);
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      addToast(apiErr.message || 'Bulk deletion failed.', 'error');
    } finally {
      setIsBulkDeleting(false);
    }
  };

  return (
    <div className="p-6 sm:p-8 max-w-7xl mx-auto space-y-6">
      {/* Page Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-6 border-b border-slate-200">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 tracking-tight flex items-center gap-2">
            <FileText className="w-6 h-6 text-indigo-600" />
            Document Management
          </h1>
          <p className="text-xs text-slate-500 mt-1">
            Search, filter, view extracted chunks, retry processing, and manage PDF documents for grounded RAG conversations.
          </p>
        </div>

        <div className="flex items-center gap-2.5 shrink-0">
          <Button
            variant="outline"
            size="sm"
            onClick={() => fetchDocuments(true)}
            isLoading={isRefreshing}
            leftIcon={<RefreshCw className="w-3.5 h-3.5" />}
            title="Refresh document statuses"
          >
            Refresh
          </Button>

          <Button
            variant="primary"
            size="sm"
            onClick={() => setIsUploadModalOpen(true)}
            leftIcon={<UploadCloud className="w-4 h-4" />}
            data-testid="open-upload-modal-button"
          >
            Upload PDF
          </Button>
        </div>
      </div>

      {/* Search, Filter, and Sort Controls */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-3 p-4 bg-white rounded-xl border border-slate-200/90 shadow-2xs">
        {/* Search Input */}
        <div className="relative flex-1 max-w-md">
          <Search className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2" />
          <input
            type="text"
            placeholder="Search documents by filename..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full pl-9 pr-8 py-2 text-xs rounded-lg border border-slate-200 bg-slate-50 focus:bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 transition"
            data-testid="document-search-input"
          />
          {searchQuery && (
            <button
              onClick={() => setSearchQuery('')}
              className="absolute right-2.5 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600 p-0.5"
              title="Clear search"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          )}
        </div>

        {/* Filter Tabs & Sort Dropdown */}
        <div className="flex flex-wrap items-center gap-2.5">
          {/* Status Tabs */}
          <div className="flex items-center bg-slate-100 p-1 rounded-lg text-xs" data-testid="status-filter-group">
            {(['ALL', 'READY', 'PROCESSING', 'FAILED'] as DocumentStatusFilter[]).map((st) => (
              <button
                key={st}
                onClick={() => setStatusFilter(st)}
                className={`px-3 py-1 rounded-md font-medium transition flex items-center gap-1.5 ${
                  statusFilter === st
                    ? 'bg-white text-indigo-700 shadow-2xs'
                    : 'text-slate-600 hover:text-slate-900'
                }`}
                data-testid={`filter-${st.toLowerCase()}`}
              >
                <span>{st === 'ALL' ? 'All' : st.charAt(0) + st.slice(1).toLowerCase()}</span>
                <span
                  className={`px-1.5 py-0.2 rounded-full text-2xs ${
                    statusFilter === st ? 'bg-indigo-50 text-indigo-700 font-semibold' : 'bg-slate-200 text-slate-600'
                  }`}
                >
                  {statusCounts[st]}
                </span>
              </button>
            ))}
          </div>

          {/* Sort Dropdown */}
          <div className="flex items-center gap-1.5 text-xs text-slate-600">
            <ArrowUpDown className="w-3.5 h-3.5 text-slate-400" />
            <select
              value={sortOption}
              onChange={(e) => setSortOption(e.target.value as DocumentSortOption)}
              className="px-2.5 py-1.5 rounded-lg border border-slate-200 bg-white text-slate-800 text-xs focus:outline-none focus:ring-2 focus:ring-indigo-500"
              data-testid="document-sort-select"
            >
              <option value="newest">Upload Date (Newest)</option>
              <option value="oldest">Upload Date (Oldest)</option>
              <option value="name_asc">Filename (A - Z)</option>
              <option value="name_desc">Filename (Z - A)</option>
              <option value="size_desc">File Size (Largest)</option>
              <option value="size_asc">File Size (Smallest)</option>
            </select>
          </div>
        </div>
      </div>

      {/* Bulk Action Bar (Visible when documents are selected) */}
      {selectedIds.length > 0 && (
        <div
          className="flex items-center justify-between p-3.5 bg-indigo-50/90 border border-indigo-200/80 rounded-xl text-xs text-indigo-900 shadow-2xs animate-fade-in"
          data-testid="bulk-action-bar"
        >
          <div className="flex items-center gap-3">
            <button
              onClick={handleSelectAllVisible}
              className="flex items-center gap-1.5 font-semibold text-indigo-700 hover:text-indigo-900"
            >
              {areAllVisibleSelected ? (
                <CheckSquare className="w-4 h-4 text-indigo-600" />
              ) : (
                <Square className="w-4 h-4 text-slate-400" />
              )}
              <span>{areAllVisibleSelected ? 'Deselect visible' : 'Select all visible'}</span>
            </button>
            <span className="text-indigo-300">|</span>
            <span className="font-medium" data-testid="selected-count-badge">
              {selectedIds.length} {selectedIds.length === 1 ? 'document' : 'documents'} selected
            </span>
          </div>

          <div className="flex items-center gap-2">
            <Button
              variant="ghost"
              size="sm"
              onClick={handleClearSelection}
              className="text-indigo-700 hover:bg-indigo-100"
            >
              Clear selection
            </Button>
            <Button
              variant="danger"
              size="sm"
              onClick={() => setIsBulkDeleteModalOpen(true)}
              leftIcon={<Trash2 className="w-3.5 h-3.5" />}
              data-testid="bulk-delete-button"
            >
              Delete Selected ({selectedIds.length})
            </Button>
          </div>
        </div>
      )}

      {/* Document List Grid */}
      <DocumentList
        documents={paginatedDocuments}
        isLoading={isLoading}
        selectedIds={selectedIds}
        onToggleSelect={handleToggleSelect}
        onViewChunks={(doc) => setChunkViewerDoc(doc)}
        onProcess={handleProcess}
        onDelete={(id) => {
          const doc = documents.find((d) => d.id === id);
          if (doc) setDocToDelete(doc);
        }}
        onRetryDelete={handleRetryDelete}
        onUploadClick={() => setIsUploadModalOpen(true)}
        emptyTitle={
          searchQuery || statusFilter !== 'ALL'
            ? 'No matching documents found'
            : 'No documents uploaded yet'
        }
        emptyDescription={
          searchQuery || statusFilter !== 'ALL'
            ? 'Try adjusting your search query or status filter to see other documents.'
            : 'Upload research papers, quarterly reports, contracts, or manuals to begin grounded RAG conversations.'
        }
      />

      {/* Pagination Controls */}
      {totalItems > 0 && (
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pt-4 border-t border-slate-200 text-xs text-slate-500">
          <div className="flex items-center gap-2">
            <span>
              Showing {Math.min((currentPage - 1) * pageSize + 1, totalItems)} to{' '}
              {Math.min(currentPage * pageSize, totalItems)} of {totalItems} documents
            </span>
            <span className="text-slate-300">•</span>
            <div className="flex items-center gap-1">
              <span>Per page:</span>
              <select
                value={pageSize}
                onChange={(e) => {
                  setPageSize(Number(e.target.value));
                  setCurrentPage(1);
                }}
                className="px-2 py-0.5 rounded border border-slate-200 bg-white text-slate-700 font-medium focus:outline-none focus:ring-1 focus:ring-indigo-500"
                data-testid="page-size-select"
              >
                <option value={6}>6</option>
                <option value={12}>12</option>
                <option value={24}>24</option>
              </select>
            </div>
          </div>

          <div className="flex items-center gap-1.5 self-center sm:self-auto">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setCurrentPage((p) => Math.max(1, p - 1))}
              disabled={currentPage <= 1}
              leftIcon={<ChevronLeft className="w-3.5 h-3.5" />}
              data-testid="prev-page-button"
            >
              Previous
            </Button>

            <span className="px-3 py-1 font-semibold text-slate-700 bg-slate-100 rounded-md">
              {currentPage} / {totalPages}
            </span>

            <Button
              variant="outline"
              size="sm"
              onClick={() => setCurrentPage((p) => Math.min(totalPages, p + 1))}
              disabled={currentPage >= totalPages}
              rightIcon={<ChevronRight className="w-3.5 h-3.5" />}
              data-testid="next-page-button"
            >
              Next
            </Button>
          </div>
        </div>
      )}

      {/* Upload Modal */}
      <DocumentUploadModal
        isOpen={isUploadModalOpen}
        onClose={() => setIsUploadModalOpen(false)}
        onSuccess={(newDoc) => {
          setDocuments((prev) => [newDoc, ...prev.filter((d) => d.id !== newDoc.id)]);
        }}
      />

      {/* Chunk Viewer Modal */}
      <DocumentChunkViewerModal
        document={chunkViewerDoc}
        isOpen={!!chunkViewerDoc}
        onClose={() => setChunkViewerDoc(null)}
      />

      {/* Single Delete Confirmation Modal */}
      <Modal
        isOpen={!!docToDelete}
        onClose={() => !isDeleting && setDocToDelete(null)}
        title="Confirm Document Deletion"
        description="Are you sure you want to delete this document? This action is permanent."
        maxWidth="sm"
      >
        <div className="space-y-4">
          <div className="flex items-start gap-3 p-3 rounded-lg bg-rose-50 border border-rose-200 text-xs text-rose-800">
            <AlertTriangle className="w-5 h-5 text-rose-600 shrink-0 mt-0.5" />
            <div>
              <p className="font-semibold">Destructive Action</p>
              <p className="mt-0.5">
                Deleting &ldquo;{docToDelete?.originalFileName}&rdquo; will purge all extracted text chunks and vector embeddings from Qdrant, and delete the file from private disk storage.
              </p>
            </div>
          </div>

          <div className="flex justify-end gap-3 pt-3 border-t border-slate-100">
            <Button
              variant="ghost"
              onClick={() => setDocToDelete(null)}
              disabled={isDeleting}
            >
              Cancel
            </Button>
            <Button
              variant="danger"
              onClick={handleDeleteConfirm}
              isLoading={isDeleting}
              data-testid="confirm-delete-button"
            >
              Delete Document
            </Button>
          </div>
        </div>
      </Modal>

      {/* Bulk Delete Confirmation Modal */}
      <Modal
        isOpen={isBulkDeleteModalOpen}
        onClose={() => !isBulkDeleting && setIsBulkDeleteModalOpen(false)}
        title="Confirm Bulk Document Deletion"
        description={`You are about to permanently delete ${selectedIds.length} selected document(s).`}
        maxWidth="md"
      >
        <div className="space-y-4">
          <div className="flex items-start gap-3 p-3 rounded-lg bg-rose-50 border border-rose-200 text-xs text-rose-800">
            <AlertTriangle className="w-5 h-5 text-rose-600 shrink-0 mt-0.5" />
            <div>
              <p className="font-semibold">Permanent Cascaded Removal</p>
              <p className="mt-0.5">
                This operation will permanently purge all extracted relational text chunks, delete all corresponding vector points from Qdrant, and delete the physical files from server storage for all selected documents.
              </p>
            </div>
          </div>

          {/* List of selected documents */}
          <div className="max-h-40 overflow-y-auto bg-slate-50 p-2.5 rounded-lg border border-slate-200 text-xs space-y-1">
            {documents
              .filter((d) => selectedIds.includes(d.id))
              .map((d) => (
                <div key={d.id} className="flex items-center gap-2 text-slate-700 py-1 px-1.5 rounded hover:bg-slate-100">
                  <FileText className="w-3.5 h-3.5 text-slate-400 shrink-0" />
                  <span className="truncate font-medium">{d.originalFileName}</span>
                </div>
              ))}
          </div>

          <div className="flex justify-end gap-3 pt-3 border-t border-slate-100">
            <Button
              variant="ghost"
              onClick={() => setIsBulkDeleteModalOpen(false)}
              disabled={isBulkDeleting}
            >
              Cancel
            </Button>
            <Button
              variant="danger"
              onClick={handleBulkDeleteConfirm}
              isLoading={isBulkDeleting}
              data-testid="confirm-bulk-delete-button"
            >
              Delete {selectedIds.length} Documents
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
};
