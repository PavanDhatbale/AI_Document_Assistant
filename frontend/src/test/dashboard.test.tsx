import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { DashboardPage } from '../pages/DashboardPage';
import { AuthProvider } from '../context/AuthContext';
import { ToastProvider } from '../context/ToastContext';
import { documentsApi } from '../api/documents';
import { conversationsApi } from '../api/conversations';
import { authApi } from '../api/auth';
import { tokenManager } from '../api/client';
import { DocumentResponse } from '../types/document';
import { ConversationResponse } from '../types/conversation';

// Mock dependencies
vi.mock('../api/documents', () => ({
  documentsApi: {
    listDocuments: vi.fn(),
    uploadDocument: vi.fn(),
    processDocument: vi.fn(),
  },
}));

vi.mock('../api/conversations', () => ({
  conversationsApi: {
    listConversations: vi.fn(),
    createConversation: vi.fn(),
  },
}));

vi.mock('../api/auth', () => ({
  authApi: {
    getProfile: vi.fn(),
    logout: vi.fn(),
  },
}));

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  };
});

describe('Dashboard Page Audit and Functional Tests', () => {
  const mockUser = {
    id: 'user-dash-1',
    email: 'dash.user@example.com',
    displayName: 'Dashboard Tester',
    role: 'ROLE_USER',
    createdAt: '2026-10-24T12:00:00Z',
  };

  const sampleDocuments: DocumentResponse[] = [
    {
      id: 'doc-1',
      originalFileName: 'Quantum_Physics.pdf',
      fileSizeBytes: 204800,
      totalPages: 10,
      totalChunks: 25,
      status: 'READY',
      createdAt: '2026-10-24T12:00:00Z',
      updatedAt: '2026-10-24T12:01:00Z',
    },
    {
      id: 'doc-2',
      originalFileName: 'Neural_Networks.pdf',
      fileSizeBytes: 512000,
      totalPages: 18,
      totalChunks: 45,
      status: 'READY',
      createdAt: '2026-10-24T12:05:00Z',
      updatedAt: '2026-10-24T12:06:00Z',
    },
    {
      id: 'doc-3',
      originalFileName: 'Pending_Spec.pdf',
      fileSizeBytes: 102400,
      totalPages: null,
      totalChunks: null,
      status: 'PENDING',
      createdAt: '2026-10-24T12:10:00Z',
      updatedAt: '2026-10-24T12:10:00Z',
    },
    {
      id: 'doc-4',
      originalFileName: 'Corrupt_File.pdf',
      fileSizeBytes: 50000,
      totalPages: null,
      totalChunks: null,
      status: 'FAILED',
      errorMessage: 'Missing EOF marker',
      createdAt: '2026-10-24T12:15:00Z',
      updatedAt: '2026-10-24T12:15:00Z',
    },
  ];

  const sampleConversations: ConversationResponse[] = [
    {
      id: 'conv-1',
      title: 'Quantum Computing Research',
      createdAt: '2026-10-24T12:20:00Z',
      updatedAt: '2026-10-24T12:25:00Z',
      messageCount: 8,
      documentCount: 1,
      documentIds: ['doc-1'],
    },
    {
      id: 'conv-2',
      title: 'Neural Architecture Search',
      createdAt: '2026-10-24T12:30:00Z',
      updatedAt: '2026-10-24T12:35:00Z',
      messageCount: 4,
      documentCount: 1,
      documentIds: ['doc-2'],
    },
  ];

  beforeEach(() => {
    vi.clearAllMocks();
    tokenManager.setToken('mock-jwt-token');
    (authApi.getProfile as any).mockResolvedValue(mockUser);
    (documentsApi.listDocuments as any).mockResolvedValue(sampleDocuments);
    (conversationsApi.listConversations as any).mockResolvedValue(sampleConversations);
  });

  const renderDashboard = () =>
    render(
      <MemoryRouter>
        <ToastProvider>
          <AuthProvider>
            <DashboardPage />
          </AuthProvider>
        </ToastProvider>
      </MemoryRouter>
    );

  it('1. Calculates and displays all 4 dashboard statistics accurately from API data', async () => {
    renderDashboard();

    // Total documents: 4 documents total (2 READY, 1 PENDING, 1 FAILED)
    await waitFor(() => {
      expect(screen.getByTestId('stat-total-documents-value')).toHaveTextContent('4');
    });

    // Ready for Chat: only 2 READY documents
    expect(screen.getByTestId('stat-ready-documents-value')).toHaveTextContent('2');

    // Vector Chunks: only from READY documents: 25 + 45 = 70 chunks
    expect(screen.getByTestId('stat-vector-chunks-value')).toHaveTextContent('70');

    // Conversations: 2 active conversations
    expect(screen.getByTestId('stat-conversations-value')).toHaveTextContent('2');
  });

  it('2. Excludes chunks from PENDING and FAILED documents from queryable vector chunks metric', async () => {
    (documentsApi.listDocuments as any).mockResolvedValue([
      { id: 'd-1', originalFileName: 'Valid.pdf', status: 'READY', totalChunks: 15, fileSizeBytes: 100, createdAt: '', updatedAt: '' },
      { id: 'd-2', originalFileName: 'Pending.pdf', status: 'PENDING', totalChunks: null, fileSizeBytes: 100, createdAt: '', updatedAt: '' },
      { id: 'd-3', originalFileName: 'Failed.pdf', status: 'FAILED', totalChunks: null, fileSizeBytes: 100, createdAt: '', updatedAt: '' },
    ]);

    renderDashboard();

    await waitFor(() => {
      expect(screen.getByTestId('stat-total-documents-value')).toHaveTextContent('3');
      expect(screen.getByTestId('stat-ready-documents-value')).toHaveTextContent('1');
      expect(screen.getByTestId('stat-vector-chunks-value')).toHaveTextContent('15');
    });
  });

  it('3. Renders recent documents list and navigates to /documents on click', async () => {
    renderDashboard();

    await waitFor(() => {
      expect(screen.getByTestId('recent-document-item-doc-1')).toBeInTheDocument();
    });

    expect(screen.getByText('Quantum_Physics.pdf')).toBeInTheDocument();
    expect(screen.getByText('Neural_Networks.pdf')).toBeInTheDocument();

    // Click on a recent document item
    fireEvent.click(screen.getByTestId('recent-document-item-doc-1'));
    expect(mockNavigate).toHaveBeenCalledWith('/documents');

    // Click "View all documents" link
    fireEvent.click(screen.getByTestId('view-all-documents-link'));
    expect(mockNavigate).toHaveBeenCalledWith('/documents');
  });

  it('4. Renders recent conversations list and navigates to conversation on click', async () => {
    renderDashboard();

    await waitFor(() => {
      expect(screen.getByTestId('recent-conversation-item-conv-1')).toBeInTheDocument();
    });

    expect(screen.getByText('Quantum Computing Research')).toBeInTheDocument();
    expect(screen.getByText('8 msgs')).toBeInTheDocument();

    // Click conversation navigates with query id
    fireEvent.click(screen.getByTestId('recent-conversation-item-conv-1'));
    expect(mockNavigate).toHaveBeenCalledWith('/conversations?id=conv-1');

    // Click "View all conversations" link
    fireEvent.click(screen.getByTestId('view-all-conversations-link'));
    expect(mockNavigate).toHaveBeenCalledWith('/conversations');
  });

  it('5. New Chat shortcut creates conversation and navigates to it', async () => {
    (conversationsApi.createConversation as any).mockResolvedValueOnce({
      id: 'conv-new-99',
      title: 'New Conversation',
      createdAt: '',
      updatedAt: '',
      documentCount: 0,
      messageCount: 0,
      documentIds: [],
    });

    renderDashboard();

    const newChatBtn = screen.getByTestId('new-chat-shortcut');
    fireEvent.click(newChatBtn);

    await waitFor(() => {
      expect(conversationsApi.createConversation).toHaveBeenCalled();
      expect(mockNavigate).toHaveBeenCalledWith('/conversations?id=conv-new-99');
    });
  });

  it('6. Displays clean empty states when user has 0 documents and 0 conversations', async () => {
    (documentsApi.listDocuments as any).mockResolvedValue([]);
    (conversationsApi.listConversations as any).mockResolvedValue([]);

    renderDashboard();

    await waitFor(() => {
      expect(screen.getByTestId('stat-total-documents-value')).toHaveTextContent('0');
      expect(screen.getByTestId('stat-ready-documents-value')).toHaveTextContent('0');
      expect(screen.getByTestId('stat-vector-chunks-value')).toHaveTextContent('0');
      expect(screen.getByTestId('stat-conversations-value')).toHaveTextContent('0');
    });

    expect(screen.getByTestId('recent-documents-empty')).toHaveTextContent('No documents uploaded yet.');
    expect(screen.getByTestId('recent-conversations-empty')).toHaveTextContent('No conversations yet.');
  });

  it('7. Handles API error with visible error banner and retry action', async () => {
    (documentsApi.listDocuments as any).mockRejectedValueOnce(new Error('Network error loading documents'));

    renderDashboard();

    // Error banner should appear with message and Retry button
    expect(await screen.findByTestId('dashboard-error-banner')).toBeInTheDocument();
    expect(screen.getByText('Network error loading documents')).toBeInTheDocument();

    // Setup success for retry
    (documentsApi.listDocuments as any).mockResolvedValue(sampleDocuments);

    // Click retry
    fireEvent.click(screen.getByTestId('retry-dashboard-button'));

    await waitFor(() => {
      expect(screen.queryByTestId('dashboard-error-banner')).not.toBeInTheDocument();
      expect(screen.getByTestId('stat-total-documents-value')).toHaveTextContent('4');
    });
  });
});
