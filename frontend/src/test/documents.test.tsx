import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { DocumentUploadModal } from '../components/documents/DocumentUploadModal';
import { DocumentCard } from '../components/documents/DocumentCard';
import { StatusBadge } from '../components/documents/StatusBadge';
import { DocumentChunkViewerModal } from '../components/documents/DocumentChunkViewerModal';
import { DocumentsPage } from '../pages/DocumentsPage';
import { ToastProvider } from '../context/ToastContext';
import { documentsApi } from '../api/documents';
import { DocumentChunkResponse, DocumentResponse } from '../types/document';

vi.mock('../api/documents', () => ({
  documentsApi: {
    uploadDocument: vi.fn(),
    processDocument: vi.fn(),
    deleteDocument: vi.fn(),
    retryDeleteDocument: vi.fn(),
    listDocuments: vi.fn(),
    bulkDelete: vi.fn(),
    getDocumentChunks: vi.fn(),
  },
}));

describe('Document Management and Status Handling', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // 1. Document upload validation
  it('rejects non-PDF files during upload selection', async () => {
    const onClose = vi.fn();
    const onSuccess = vi.fn();

    render(
      <ToastProvider>
        <DocumentUploadModal isOpen={true} onClose={onClose} onSuccess={onSuccess} />
      </ToastProvider>
    );

    const input = screen.getByTestId('file-upload-input');
    const txtFile = new File(['hello world'], 'test.txt', { type: 'text/plain' });

    fireEvent.change(input, { target: { files: [txtFile] } });

    expect(await screen.findByText('Only PDF documents (.pdf) are supported.')).toBeInTheDocument();
    expect(documentsApi.uploadDocument).not.toHaveBeenCalled();
  });

  it('rejects empty files with 0 bytes', async () => {
    const onClose = vi.fn();
    const onSuccess = vi.fn();

    render(
      <ToastProvider>
        <DocumentUploadModal isOpen={true} onClose={onClose} onSuccess={onSuccess} />
      </ToastProvider>
    );

    const input = screen.getByTestId('file-upload-input');
    const emptyPdf = new File([], 'empty.pdf', { type: 'application/pdf' });

    fireEvent.change(input, { target: { files: [emptyPdf] } });

    expect(await screen.findByText('The selected file is empty.')).toBeInTheDocument();
  });

  it('accepts valid PDF file and uploads successfully', async () => {
    const onClose = vi.fn();
    const onSuccess = vi.fn();

    const mockDoc: DocumentResponse = {
      id: 'doc-101',
      originalFileName: 'quarterly_report.pdf',
      fileSizeBytes: 204800,
      totalPages: 10,
      totalChunks: 25,
      status: 'READY',
      createdAt: '2026-10-24T12:00:00Z',
      updatedAt: '2026-10-24T12:05:00Z',
    };

    (documentsApi.uploadDocument as any).mockResolvedValueOnce(mockDoc);
    (documentsApi.processDocument as any).mockResolvedValueOnce(mockDoc);

    render(
      <ToastProvider>
        <DocumentUploadModal isOpen={true} onClose={onClose} onSuccess={onSuccess} />
      </ToastProvider>
    );

    const input = screen.getByTestId('file-upload-input');
    const validPdf = new File(['%PDF-1.4 mock content'], 'quarterly_report.pdf', {
      type: 'application/pdf',
    });

    fireEvent.change(input, { target: { files: [validPdf] } });

    expect(await screen.findByText('quarterly_report.pdf')).toBeInTheDocument();

    const uploadBtn = screen.getByRole('button', { name: /Upload Document/i });
    fireEvent.click(uploadBtn);

    await waitFor(() => {
      expect(documentsApi.uploadDocument).toHaveBeenCalled();
      expect(onSuccess).toHaveBeenCalledWith(mockDoc);
    });
  });

  // 2. Rendering document statuses correctly
  it('renders distinct status badges for all document lifecycle phases', () => {
    const { rerender } = render(<StatusBadge status="PENDING" />);
    expect(screen.getByText('Pending')).toBeInTheDocument();

    rerender(<StatusBadge status="PROCESSING" />);
    expect(screen.getByText('Processing')).toBeInTheDocument();

    rerender(<StatusBadge status="READY" />);
    expect(screen.getByText('Ready')).toBeInTheDocument();

    rerender(<StatusBadge status="FAILED" errorMessage="Corrupt PDF trailer" />);
    expect(screen.getByText('Failed')).toBeInTheDocument();

    rerender(<StatusBadge status="DELETING" />);
    expect(screen.getByText('Deleting')).toBeInTheDocument();

    rerender(<StatusBadge status="DELETION_FAILED" />);
    expect(screen.getByText('Deletion Failed')).toBeInTheDocument();
  });

  // 3. Action buttons in DocumentCard
  it('displays action buttons according to document status in DocumentCard', () => {
    const mockOnProcess = vi.fn();
    const mockOnDelete = vi.fn();
    const mockOnRetryDelete = vi.fn();
    const mockOnViewChunks = vi.fn();

    const pendingDoc: DocumentResponse = {
      id: 'doc-pending-1',
      originalFileName: 'pending.pdf',
      fileSizeBytes: 1024,
      totalPages: null,
      totalChunks: null,
      status: 'PENDING',
      createdAt: '2026-10-24T12:00:00Z',
      updatedAt: '2026-10-24T12:00:00Z',
    };

    const { rerender } = render(
      <DocumentCard
        document={pendingDoc}
        onProcess={mockOnProcess}
        onDelete={mockOnDelete}
        onRetryDelete={mockOnRetryDelete}
        onViewChunks={mockOnViewChunks}
      />
    );

    // Pending should show Process Now button
    expect(screen.getByRole('button', { name: /Process Now/i })).toBeInTheDocument();

    // Failed should show Retry Process button
    const failedDoc: DocumentResponse = {
      ...pendingDoc,
      status: 'FAILED',
      errorMessage: 'Failed to extract text',
    };

    rerender(
      <DocumentCard
        document={failedDoc}
        onProcess={mockOnProcess}
        onDelete={mockOnDelete}
        onRetryDelete={mockOnRetryDelete}
        onViewChunks={mockOnViewChunks}
      />
    );

    expect(screen.getByRole('button', { name: /Retry Process/i })).toBeInTheDocument();

    // Ready should show View Chunks button
    const readyDoc: DocumentResponse = {
      ...pendingDoc,
      status: 'READY',
      totalChunks: 12,
      totalPages: 4,
    };

    rerender(
      <DocumentCard
        document={readyDoc}
        onProcess={mockOnProcess}
        onDelete={mockOnDelete}
        onRetryDelete={mockOnRetryDelete}
        onViewChunks={mockOnViewChunks}
      />
    );

    expect(screen.getByRole('button', { name: /View Chunks/i })).toBeInTheDocument();

    // Deletion Failed should show Retry Deletion button
    const failedDeletionDoc: DocumentResponse = {
      ...pendingDoc,
      status: 'DELETION_FAILED',
    };

    rerender(
      <DocumentCard
        document={failedDeletionDoc}
        onProcess={mockOnProcess}
        onDelete={mockOnDelete}
        onRetryDelete={mockOnRetryDelete}
        onViewChunks={mockOnViewChunks}
      />
    );

    expect(screen.getByRole('button', { name: /Retry Deletion/i })).toBeInTheDocument();
  });

  // 4. Chunk Viewer Modal functionality
  it('renders extracted chunks, page numbers, token count, and supports search filtering', async () => {
    const mockDoc: DocumentResponse = {
      id: 'doc-ready-1',
      originalFileName: 'handbook.pdf',
      fileSizeBytes: 50000,
      totalPages: 2,
      totalChunks: 2,
      status: 'READY',
      createdAt: '2026-10-24T12:00:00Z',
      updatedAt: '2026-10-24T12:00:00Z',
    };

    const mockChunks: DocumentChunkResponse[] = [
      {
        id: 'chunk-1',
        chunkIndex: 0,
        pageNumber: 1,
        chunkText: 'Introduction to enterprise distributed databases.',
        tokenCount: 45,
      },
      {
        id: 'chunk-2',
        chunkIndex: 1,
        pageNumber: 2,
        chunkText: 'Security guidelines for encryption at rest and in transit.',
        tokenCount: 60,
      },
    ];

    (documentsApi.getDocumentChunks as any).mockResolvedValueOnce(mockChunks);

    render(
      <DocumentChunkViewerModal
        document={mockDoc}
        isOpen={true}
        onClose={vi.fn()}
      />
    );

    // Verify modal headers and chunk items
    expect(await screen.findByText('Extracted Document Chunks')).toBeInTheDocument();
    expect(screen.getByText('Chunk 0')).toBeInTheDocument();
    expect(screen.getByText('Page 1')).toBeInTheDocument();
    expect(screen.getByText('45 tokens')).toBeInTheDocument();
    expect(screen.getByText('Introduction to enterprise distributed databases.')).toBeInTheDocument();

    expect(screen.getByText('Chunk 1')).toBeInTheDocument();
    expect(screen.getByText('Page 2')).toBeInTheDocument();
    expect(screen.getByText('Security guidelines for encryption at rest and in transit.')).toBeInTheDocument();

    // Test search inside chunk viewer
    const searchInput = screen.getByTestId('chunk-search-input');
    fireEvent.change(searchInput, { target: { value: 'encryption' } });

    expect(screen.getByText('Security guidelines for encryption at rest and in transit.')).toBeInTheDocument();
    expect(screen.queryByText('Introduction to enterprise distributed databases.')).not.toBeInTheDocument();
  });

  // 5. Full DocumentsPage search, status filtering, and sorting
  it('supports search by filename, status filter tabs, and sorting in DocumentsPage', async () => {
    const sampleDocs: DocumentResponse[] = [
      {
        id: 'doc-1',
        originalFileName: 'Alpha_Contract.pdf',
        fileSizeBytes: 10000,
        totalPages: 2,
        totalChunks: 5,
        status: 'READY',
        createdAt: '2026-10-24T10:00:00Z',
        updatedAt: '2026-10-24T10:00:00Z',
      },
      {
        id: 'doc-2',
        originalFileName: 'Beta_Invoice.pdf',
        fileSizeBytes: 50000,
        totalPages: 1,
        totalChunks: null,
        status: 'PENDING',
        createdAt: '2026-10-24T11:00:00Z',
        updatedAt: '2026-10-24T11:00:00Z',
      },
      {
        id: 'doc-3',
        originalFileName: 'Alpha_Notes.pdf',
        fileSizeBytes: 30000,
        totalPages: 3,
        totalChunks: null,
        status: 'FAILED',
        errorMessage: 'OCR failure',
        createdAt: '2026-10-24T12:00:00Z',
        updatedAt: '2026-10-24T12:00:00Z',
      },
    ];

    (documentsApi.listDocuments as any).mockResolvedValue(sampleDocs);

    render(
      <ToastProvider>
        <DocumentsPage />
      </ToastProvider>
    );

    // Initial load displays all 3 documents
    expect(await screen.findByText('Alpha_Contract.pdf')).toBeInTheDocument();
    expect(screen.getByText('Beta_Invoice.pdf')).toBeInTheDocument();
    expect(screen.getByText('Alpha_Notes.pdf')).toBeInTheDocument();

    // 1. Search by filename "Alpha"
    const searchInput = screen.getByTestId('document-search-input');
    fireEvent.change(searchInput, { target: { value: 'Alpha' } });

    expect(screen.getByText('Alpha_Contract.pdf')).toBeInTheDocument();
    expect(screen.getByText('Alpha_Notes.pdf')).toBeInTheDocument();
    expect(screen.queryByText('Beta_Invoice.pdf')).not.toBeInTheDocument();

    // Clear search
    fireEvent.change(searchInput, { target: { value: '' } });
    expect(screen.getByText('Beta_Invoice.pdf')).toBeInTheDocument();

    // 2. Status Filter: Ready
    const readyFilterBtn = screen.getByTestId('filter-ready');
    fireEvent.click(readyFilterBtn);

    expect(screen.getByText('Alpha_Contract.pdf')).toBeInTheDocument();
    expect(screen.queryByText('Beta_Invoice.pdf')).not.toBeInTheDocument();
    expect(screen.queryByText('Alpha_Notes.pdf')).not.toBeInTheDocument();

    // 3. Status Filter: Failed
    const failedFilterBtn = screen.getByTestId('filter-failed');
    fireEvent.click(failedFilterBtn);

    expect(screen.getByText('Alpha_Notes.pdf')).toBeInTheDocument();
    expect(screen.queryByText('Alpha_Contract.pdf')).not.toBeInTheDocument();

    // Reset filter to All
    const allFilterBtn = screen.getByTestId('filter-all');
    fireEvent.click(allFilterBtn);
    expect(screen.getByText('Alpha_Contract.pdf')).toBeInTheDocument();
    expect(screen.getByText('Beta_Invoice.pdf')).toBeInTheDocument();
    expect(screen.getByText('Alpha_Notes.pdf')).toBeInTheDocument();
  });

  // 6. Bulk Selection and Bulk Deletion
  it('supports selecting multiple documents and executing bulk deletion with confirmation', async () => {
    const sampleDocs: DocumentResponse[] = [
      {
        id: 'doc-1',
        originalFileName: 'Doc_One.pdf',
        fileSizeBytes: 10000,
        totalPages: 2,
        totalChunks: 5,
        status: 'READY',
        createdAt: '2026-10-24T10:00:00Z',
        updatedAt: '2026-10-24T10:00:00Z',
      },
      {
        id: 'doc-2',
        originalFileName: 'Doc_Two.pdf',
        fileSizeBytes: 20000,
        totalPages: 1,
        totalChunks: 2,
        status: 'READY',
        createdAt: '2026-10-24T11:00:00Z',
        updatedAt: '2026-10-24T11:00:00Z',
      },
    ];

    (documentsApi.listDocuments as any).mockResolvedValue(sampleDocs);
    (documentsApi.bulkDelete as any).mockResolvedValueOnce({
      deletedCount: 2,
      deletedIds: ['doc-1', 'doc-2'],
      failedIds: [],
      message: 'Successfully deleted 2 documents',
    });

    render(
      <ToastProvider>
        <DocumentsPage />
      </ToastProvider>
    );

    expect(await screen.findByText('Doc_One.pdf')).toBeInTheDocument();

    // Select doc-1 and doc-2
    const check1 = screen.getByTestId('select-doc-checkbox-doc-1');
    const check2 = screen.getByTestId('select-doc-checkbox-doc-2');

    fireEvent.click(check1);
    expect(screen.getByTestId('bulk-action-bar')).toBeInTheDocument();
    expect(screen.getByTestId('selected-count-badge')).toHaveTextContent('1 document selected');

    fireEvent.click(check2);
    expect(screen.getByTestId('selected-count-badge')).toHaveTextContent('2 documents selected');

    // Click "Delete Selected (2)"
    const bulkDeleteBtn = screen.getByTestId('bulk-delete-button');
    fireEvent.click(bulkDeleteBtn);

    // Bulk delete confirmation modal should appear
    expect(screen.getByText('Confirm Bulk Document Deletion')).toBeInTheDocument();
    expect(screen.getByText('Permanent Cascaded Removal')).toBeInTheDocument();

    // Confirm deletion
    const confirmBtn = screen.getByTestId('confirm-bulk-delete-button');
    fireEvent.click(confirmBtn);

    await waitFor(() => {
      expect(documentsApi.bulkDelete).toHaveBeenCalledWith(['doc-1', 'doc-2']);
      expect(screen.queryByText('Doc_One.pdf')).not.toBeInTheDocument();
      expect(screen.queryByText('Doc_Two.pdf')).not.toBeInTheDocument();
    });
  });

  // 7. Safe retry of failed processing
  it('calls processDocument when clicking Retry Process on a failed document', async () => {
    const failedDoc: DocumentResponse = {
      id: 'doc-failed-retry',
      originalFileName: 'retry_sample.pdf',
      fileSizeBytes: 15000,
      totalPages: 1,
      totalChunks: null,
      status: 'FAILED',
      errorMessage: 'Temporary network disconnect during embedding generation',
      createdAt: '2026-10-24T10:00:00Z',
      updatedAt: '2026-10-24T10:00:00Z',
    };

    const readyDoc: DocumentResponse = {
      ...failedDoc,
      status: 'READY',
      totalChunks: 5,
      errorMessage: null,
    };

    (documentsApi.listDocuments as any).mockResolvedValueOnce([failedDoc]);
    (documentsApi.processDocument as any).mockResolvedValueOnce(readyDoc);

    render(
      <ToastProvider>
        <DocumentsPage />
      </ToastProvider>
    );

    expect(await screen.findByText('retry_sample.pdf')).toBeInTheDocument();

    const retryBtn = screen.getByTestId('retry-process-btn');
    expect(retryBtn).toHaveTextContent('Retry Process');

    fireEvent.click(retryBtn);

    await waitFor(() => {
      expect(documentsApi.processDocument).toHaveBeenCalledWith('doc-failed-retry');
    });
  });
});
