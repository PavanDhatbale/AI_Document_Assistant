import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { documentsApi } from '../api/documents';
import { conversationsApi } from '../api/conversations';
import { DocumentResponse } from '../types/document';
import { ConversationResponse } from '../types/conversation';
import { StatusBadge } from '../components/documents/StatusBadge';
import { Button } from '../components/common/Button';
import { DocumentUploadModal } from '../components/documents/DocumentUploadModal';
import { useAuth } from '../context/AuthContext';
import {
  FileText,
  MessagesSquare,
  Sparkles,
  Layers,
  ArrowRight,
  Plus,
  UploadCloud,
  AlertCircle,
  RefreshCw,
} from 'lucide-react';

export const DashboardPage: React.FC = () => {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [documents, setDocuments] = useState<DocumentResponse[]>([]);
  const [conversations, setConversations] = useState<ConversationResponse[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [isUploadModalOpen, setIsUploadModalOpen] = useState(false);
  const pollingRef = useRef<NodeJS.Timeout | null>(null);

  const loadDashboardData = useCallback(async (isPolling = false) => {
    if (!isPolling) {
      setError(null);
    }
    try {
      const [docs, convs] = await Promise.all([
        documentsApi.listDocuments(),
        conversationsApi.listConversations(),
      ]);
      setDocuments(docs);
      setConversations(convs);
      setError(null);
    } catch (err: unknown) {
      // Only set visible error if not a background polling update
      if (!isPolling) {
        const msg = err instanceof Error ? err.message : 'Failed to load dashboard data. Please try again.';
        setError(msg);
      }
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadDashboardData();
  }, [loadDashboardData]);

  // Gentle background status polling if any document is PROCESSING or DELETING
  useEffect(() => {
    const hasActiveTransfers = documents.some(
      (d) => d.status === 'PROCESSING' || d.status === 'DELETING'
    );

    if (hasActiveTransfers) {
      pollingRef.current = setInterval(() => {
        loadDashboardData(true);
      }, 3000);
    } else if (pollingRef.current) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }

    return () => {
      if (pollingRef.current) {
        clearInterval(pollingRef.current);
        pollingRef.current = null;
      }
    };
  }, [documents, loadDashboardData]);

  // Derived statistics accurately reflecting document lifecycle
  const readyDocuments = documents.filter((d) => d.status === 'READY');
  // Vector chunks are only queryable for documents in READY status
  const totalChunks = readyDocuments.reduce((sum, d) => sum + (d.totalChunks || 0), 0);

  const handleCreateChat = async () => {
    try {
      const conv = await conversationsApi.createConversation();
      navigate(`/conversations?id=${conv.id}`);
    } catch {
      navigate('/conversations');
    }
  };

  return (
    <div className="p-6 sm:p-8 max-w-7xl mx-auto space-y-8">
      {/* Welcome Banner */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 p-6 bg-gradient-to-r from-slate-900 to-indigo-950 rounded-2xl text-white shadow-md">
        <div>
          <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold bg-indigo-500/20 text-indigo-300 border border-indigo-400/20 mb-2">
            <Sparkles className="w-3.5 h-3.5" />
            AI Document Assistant Platform
          </span>
          <h1 className="text-xl sm:text-2xl font-bold tracking-tight">
            Welcome, {user?.displayName || 'Colleague'}!
          </h1>
          <p className="text-xs sm:text-sm text-slate-300 mt-1 max-w-xl">
            Upload enterprise documents to build an intelligent, citation-grounded knowledge base.
          </p>
        </div>

        <div className="flex items-center gap-3 shrink-0">
          <Button
            variant="outline"
            className="bg-white/10 hover:bg-white/20 text-white border-white/20"
            onClick={() => setIsUploadModalOpen(true)}
            leftIcon={<UploadCloud className="w-4 h-4" />}
            data-testid="upload-pdf-shortcut"
          >
            Upload PDF
          </Button>
          <Button
            variant="primary"
            onClick={handleCreateChat}
            leftIcon={<Plus className="w-4 h-4" />}
            data-testid="new-chat-shortcut"
          >
            New Chat
          </Button>
        </div>
      </div>

      {/* API Error Banner with Retry */}
      {error && (
        <div
          className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 p-4 rounded-xl bg-rose-50 border border-rose-200 text-rose-800 text-xs"
          data-testid="dashboard-error-banner"
          role="alert"
        >
          <div className="flex items-center gap-2.5">
            <AlertCircle className="w-4 h-4 shrink-0 text-rose-600" />
            <span className="font-medium">{error}</span>
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={() => loadDashboardData(false)}
            leftIcon={<RefreshCw className="w-3.5 h-3.5" />}
            data-testid="retry-dashboard-button"
          >
            Retry
          </Button>
        </div>
      )}

      {/* Stats Metric Cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {/* Metric 1: Total Documents */}
        <div className="p-5 bg-white rounded-xl border border-slate-200/80 shadow-2xs" data-testid="stat-total-documents">
          <div className="flex items-center justify-between text-slate-400 mb-2">
            <span className="text-xs font-semibold uppercase tracking-wider">Total Documents</span>
            <FileText className="w-4 h-4 text-indigo-600" />
          </div>
          <p className="text-2xl font-bold text-slate-900" data-testid="stat-total-documents-value">
            {isLoading ? '—' : documents.length}
          </p>
          <p className="text-xs text-slate-500 mt-1">Uploaded to repository</p>
        </div>

        {/* Metric 2: Ready for Chat */}
        <div className="p-5 bg-white rounded-xl border border-slate-200/80 shadow-2xs" data-testid="stat-ready-documents">
          <div className="flex items-center justify-between text-slate-400 mb-2">
            <span className="text-xs font-semibold uppercase tracking-wider">Ready for Chat</span>
            <Sparkles className="w-4 h-4 text-emerald-600" />
          </div>
          <p className="text-2xl font-bold text-slate-900" data-testid="stat-ready-documents-value">
            {isLoading ? '—' : readyDocuments.length}
          </p>
          <p className="text-xs text-slate-500 mt-1">Indexed with embeddings</p>
        </div>

        {/* Metric 3: Vector Chunks */}
        <div className="p-5 bg-white rounded-xl border border-slate-200/80 shadow-2xs" data-testid="stat-vector-chunks">
          <div className="flex items-center justify-between text-slate-400 mb-2">
            <span className="text-xs font-semibold uppercase tracking-wider">Vector Chunks</span>
            <Layers className="w-4 h-4 text-indigo-600" />
          </div>
          <p className="text-2xl font-bold text-slate-900" data-testid="stat-vector-chunks-value">
            {isLoading ? '—' : totalChunks}
          </p>
          <p className="text-xs text-slate-500 mt-1">Indexed in Qdrant store</p>
        </div>

        {/* Metric 4: Conversations */}
        <div className="p-5 bg-white rounded-xl border border-slate-200/80 shadow-2xs" data-testid="stat-conversations">
          <div className="flex items-center justify-between text-slate-400 mb-2">
            <span className="text-xs font-semibold uppercase tracking-wider">Conversations</span>
            <MessagesSquare className="w-4 h-4 text-indigo-600" />
          </div>
          <p className="text-2xl font-bold text-slate-900" data-testid="stat-conversations-value">
            {isLoading ? '—' : conversations.length}
          </p>
          <p className="text-xs text-slate-500 mt-1">Active chat sessions</p>
        </div>
      </div>

      {/* Two Column Layout: Recent Documents & Recent Conversations */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Recent Documents Card */}
        <div className="p-6 bg-white rounded-2xl border border-slate-200/80 shadow-2xs" data-testid="recent-documents-card">
          <div className="flex items-center justify-between mb-4">
            <h3 className="text-sm font-bold text-slate-900 uppercase tracking-wider">
              Recent Documents
            </h3>
            <button
              onClick={() => navigate('/documents')}
              className="text-xs font-medium text-indigo-600 hover:text-indigo-800 flex items-center gap-1 focus:outline-none"
              data-testid="view-all-documents-link"
            >
              <span>View all ({documents.length})</span>
              <ArrowRight className="w-3 h-3" />
            </button>
          </div>

          {isLoading ? (
            <div className="space-y-2 py-2 animate-pulse" data-testid="recent-documents-loading">
              {[1, 2, 3].map((n) => (
                <div key={n} className="h-10 bg-slate-100 rounded-lg" />
              ))}
            </div>
          ) : documents.length === 0 ? (
            <div className="text-center py-8 text-xs text-slate-400" data-testid="recent-documents-empty">
              <FileText className="w-8 h-8 mx-auto mb-2 text-slate-300" />
              <p>No documents uploaded yet.</p>
            </div>
          ) : (
            <div className="space-y-2" data-testid="recent-documents-list">
              {documents.slice(0, 5).map((doc) => (
                <div
                  key={doc.id}
                  onClick={() => navigate('/documents')}
                  className="flex items-center justify-between p-3 rounded-lg border border-slate-100 hover:border-indigo-200 hover:bg-indigo-50/30 transition cursor-pointer text-xs group"
                  title={`View "${doc.originalFileName}" in documents`}
                  data-testid={`recent-document-item-${doc.id}`}
                >
                  <div className="flex items-center gap-2.5 min-w-0 pr-3">
                    <FileText className="w-4 h-4 text-indigo-600 shrink-0 group-hover:text-indigo-700 transition" />
                    <span className="font-semibold text-slate-800 truncate group-hover:text-indigo-900 transition" title={doc.originalFileName}>
                      {doc.originalFileName}
                    </span>
                  </div>
                  <div className="shrink-0">
                    <StatusBadge status={doc.status} />
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Recent Conversations Card */}
        <div className="p-6 bg-white rounded-2xl border border-slate-200/80 shadow-2xs" data-testid="recent-conversations-card">
          <div className="flex items-center justify-between mb-4">
            <h3 className="text-sm font-bold text-slate-900 uppercase tracking-wider">
              Recent Conversations
            </h3>
            <button
              onClick={() => navigate('/conversations')}
              className="text-xs font-medium text-indigo-600 hover:text-indigo-800 flex items-center gap-1 focus:outline-none"
              data-testid="view-all-conversations-link"
            >
              <span>View all ({conversations.length})</span>
              <ArrowRight className="w-3 h-3" />
            </button>
          </div>

          {isLoading ? (
            <div className="space-y-2 py-2 animate-pulse" data-testid="recent-conversations-loading">
              {[1, 2, 3].map((n) => (
                <div key={n} className="h-10 bg-slate-100 rounded-lg" />
              ))}
            </div>
          ) : conversations.length === 0 ? (
            <div className="text-center py-8 text-xs text-slate-400" data-testid="recent-conversations-empty">
              <MessagesSquare className="w-8 h-8 mx-auto mb-2 text-slate-300" />
              <p>No conversations yet.</p>
            </div>
          ) : (
            <div className="space-y-2" data-testid="recent-conversations-list">
              {conversations.slice(0, 5).map((conv) => (
                <div
                  key={conv.id}
                  onClick={() => navigate(`/conversations?id=${conv.id}`)}
                  className="flex items-center justify-between p-3 rounded-lg border border-slate-100 hover:border-indigo-200 hover:bg-indigo-50/30 transition cursor-pointer text-xs group"
                  title={`Open conversation: ${conv.title || 'Untitled'}`}
                  data-testid={`recent-conversation-item-${conv.id}`}
                >
                  <div className="flex items-center gap-2.5 min-w-0 pr-3">
                    <MessagesSquare className="w-4 h-4 text-indigo-600 shrink-0 group-hover:text-indigo-700 transition" />
                    <span className="font-semibold text-slate-800 truncate group-hover:text-indigo-900 transition">
                      {conv.title || 'Untitled Conversation'}
                    </span>
                  </div>
                  <span className="text-[11px] text-slate-400 shrink-0">
                    {conv.messageCount} msg{conv.messageCount !== 1 ? 's' : ''}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Upload Modal */}
      <DocumentUploadModal
        isOpen={isUploadModalOpen}
        onClose={() => setIsUploadModalOpen(false)}
        onSuccess={(newDoc) => {
          setDocuments((prev) => [newDoc, ...prev.filter((d) => d.id !== newDoc.id)]);
        }}
      />
    </div>
  );
};
