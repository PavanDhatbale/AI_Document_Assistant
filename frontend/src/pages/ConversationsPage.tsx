import React, { useState, useEffect, useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import { conversationsApi } from '../api/conversations';
import { documentsApi } from '../api/documents';
import {
  ConversationResponse,
  ConversationDetailResponse,
  MessageResponse,
} from '../types/conversation';
import { DocumentResponse } from '../types/document';
import { ConversationList } from '../components/chat/ConversationList';
import { MessageList } from '../components/chat/MessageList';
import { MessageComposer } from '../components/chat/MessageComposer';
import { DocumentScopeSelector } from '../components/chat/DocumentScopeSelector';
import { Modal } from '../components/common/Modal';
import { Button } from '../components/common/Button';
import { useToast } from '../context/ToastContext';
import { MessagesSquare, Trash2, Menu, AlertTriangle } from 'lucide-react';

export const ConversationsPage: React.FC = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const activeIdFromQuery = searchParams.get('id');

  const [conversations, setConversations] = useState<ConversationResponse[]>([]);
  const [activeConversation, setActiveConversation] = useState<ConversationDetailResponse | null>(null);
  const [allUserDocuments, setAllUserDocuments] = useState<DocumentResponse[]>([]);
  const [isLoadingConversations, setIsLoadingConversations] = useState(true);
  const [isLoadingMessages, setIsLoadingMessages] = useState(false);
  const [isSendingMessage, setIsSendingMessage] = useState(false);
  const [isMobileListOpen, setIsMobileListOpen] = useState(false);
  const [convToDelete, setConvToDelete] = useState<string | null>(null);
  const [isDeletingConv, setIsDeletingConv] = useState(false);

  const { addToast } = useToast();

  // Load conversations and user documents on mount
  useEffect(() => {
    const initData = async () => {
      try {
        const [convList, docList] = await Promise.all([
          conversationsApi.listConversations(),
          documentsApi.listDocuments(),
        ]);
        setConversations(convList);
        setAllUserDocuments(docList);

        // If query param specifies a conversation, select it; otherwise select first if available
        if (activeIdFromQuery) {
          loadConversation(activeIdFromQuery);
        } else if (convList.length > 0) {
          selectConversation(convList[0].id);
        }
      } catch {
        addToast('Failed to load conversations.', 'error');
      } finally {
        setIsLoadingConversations(false);
      }
    };

    initData();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadConversation = useCallback(async (id: string) => {
    setIsLoadingMessages(true);
    try {
      const detail = await conversationsApi.getConversation(id);
      setActiveConversation(detail);
      setSearchParams({ id });
    } catch {
      addToast('Conversation not found or access denied.', 'error');
      setActiveConversation(null);
    } finally {
      setIsLoadingMessages(false);
    }
  }, [addToast, setSearchParams]);

  const selectConversation = (id: string) => {
    loadConversation(id);
    setIsMobileListOpen(false);
  };

  const handleCreateConversation = async () => {
    try {
      const newConv = await conversationsApi.createConversation();
      setConversations((prev) => [newConv, ...prev]);
      selectConversation(newConv.id);
      addToast('New conversation created.', 'success');
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      addToast(apiErr.message || 'Failed to create conversation.', 'error');
    }
  };

  const handleDeleteConversationConfirm = async () => {
    if (!convToDelete) return;
    setIsDeletingConv(true);
    try {
      await conversationsApi.deleteConversation(convToDelete);
      addToast('Conversation deleted.', 'success');
      const remaining = conversations.filter((c) => c.id !== convToDelete);
      setConversations(remaining);

      if (activeConversation?.id === convToDelete) {
        if (remaining.length > 0) {
          selectConversation(remaining[0].id);
        } else {
          setActiveConversation(null);
          setSearchParams({});
        }
      }
      setConvToDelete(null);
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      addToast(apiErr.message || 'Failed to delete conversation.', 'error');
    } finally {
      setIsDeletingConv(false);
    }
  };

  const handleSendMessage = async (content: string): Promise<boolean> => {
    if (!activeConversation) return false;

    setIsSendingMessage(true);

    // Optimistically create temporary user message in UI while API processes
    const tempUserMsg: MessageResponse = {
      id: `temp-${Date.now()}`,
      role: 'USER',
      content,
      tokenCount: 0,
      createdAt: new Date().toISOString(),
      citations: [],
    };

    setActiveConversation((prev) =>
      prev ? { ...prev, messages: [...prev.messages, tempUserMsg] } : null
    );

    try {
      const answerResponse = await conversationsApi.sendMessage(activeConversation.id, content);

      // Replace temp message with server response and append assistant message
      setActiveConversation((prev) => {
        if (!prev) return null;
        const filtered = prev.messages.filter((m) => m.id !== tempUserMsg.id);
        return {
          ...prev,
          messages: [...filtered, answerResponse.userMessage, answerResponse.assistantMessage],
        };
      });

      // Update conversation in sidebar list
      setConversations((prev) =>
        prev.map((c) =>
          c.id === activeConversation.id
            ? { ...c, messageCount: c.messageCount + 2, updatedAt: new Date().toISOString() }
            : c
        )
      );

      return true;
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      const friendlyMsg = apiErr.message || 'Unable to retrieve answer from AI assistant. Please try again.';
      addToast(friendlyMsg, 'error');

      // Revert the optimistic message so user knows it failed
      setActiveConversation((prev) =>
        prev ? { ...prev, messages: prev.messages.filter((m) => m.id !== tempUserMsg.id) } : null
      );
      return false; // Tells MessageComposer to retain typed draft
    } finally {
      setIsSendingMessage(false);
    }
  };

  const handleAddDocuments = async (docIds: string[]) => {
    if (!activeConversation) return;
    try {
      const updatedDocs = await conversationsApi.addDocumentsToConversation(
        activeConversation.id,
        docIds
      );
      setActiveConversation((prev) => (prev ? { ...prev, documents: updatedDocs } : null));
      addToast(`Attached ${docIds.length} document(s) to conversation scope.`, 'success');
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      addToast(apiErr.message || 'Failed to attach documents.', 'error');
    }
  };

  const handleRemoveDocument = async (docId: string) => {
    if (!activeConversation) return;
    try {
      await conversationsApi.removeDocumentFromConversation(activeConversation.id, docId);
      setActiveConversation((prev) =>
        prev ? { ...prev, documents: prev.documents.filter((d) => d.id !== docId) } : null
      );
      addToast('Document removed from conversation scope.', 'info');
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      addToast(apiErr.message || 'Failed to remove document.', 'error');
    }
  };

  return (
    <div className="h-[calc(100vh-4rem)] lg:h-screen flex flex-col md:flex-row overflow-hidden bg-slate-100">
      {/* Mobile Drawer Backdrop */}
      {isMobileListOpen && (
        <div
          className="fixed inset-0 z-30 bg-slate-900/40 backdrop-blur-xs md:hidden"
          onClick={() => setIsMobileListOpen(false)}
          aria-hidden="true"
        />
      )}

      {/* Conversations Left Panel */}
      <div
        className={`fixed md:static inset-y-0 left-0 z-30 w-72 lg:w-80 h-full transform transition-transform duration-200 ease-in-out md:translate-x-0 ${
          isMobileListOpen ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
        <ConversationList
          conversations={conversations}
          activeConversationId={activeConversation?.id || null}
          onSelectConversation={selectConversation}
          onCreateConversation={handleCreateConversation}
          onDeleteConversation={(id, e) => {
            e.stopPropagation();
            setConvToDelete(id);
          }}
          isLoading={isLoadingConversations}
        />
      </div>

      {/* Main Chat Area */}
      <div className="flex-1 flex flex-col h-full min-w-0 bg-slate-50">
        {activeConversation ? (
          <>
            {/* Top Bar for active conversation */}
            <div className="h-14 px-4 bg-white border-b border-slate-200/80 flex items-center justify-between shrink-0">
              <div className="flex items-center gap-3 min-w-0">
                <button
                  onClick={() => setIsMobileListOpen((prev) => !prev)}
                  className="p-1.5 rounded-lg text-slate-500 hover:text-slate-900 hover:bg-slate-100 md:hidden"
                  aria-label="Toggle conversations list"
                >
                  <Menu className="w-5 h-5" />
                </button>
                <div className="min-w-0">
                  <h2 className="text-sm font-bold text-slate-900 truncate">
                    {activeConversation.title || 'Untitled Conversation'}
                  </h2>
                  <p className="text-[11px] text-slate-500">
                    {activeConversation.messages.length} messages
                  </p>
                </div>
              </div>

              <div className="flex items-center gap-2">
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => setConvToDelete(activeConversation.id)}
                  className="text-slate-400 hover:text-rose-600 hover:bg-rose-50"
                  aria-label="Delete this conversation"
                >
                  <Trash2 className="w-4 h-4" />
                </Button>
              </div>
            </div>

            {/* Document Scope Bar */}
            <DocumentScopeSelector
              conversationId={activeConversation.id}
              scopedDocuments={activeConversation.documents}
              allUserDocuments={allUserDocuments}
              onAddDocuments={handleAddDocuments}
              onRemoveDocument={handleRemoveDocument}
            />

            {/* Messages Scroll Area */}
            <MessageList
              messages={activeConversation.messages}
              isWaitingForAnswer={isSendingMessage}
            />

            {/* Input Form */}
            <MessageComposer
              onSend={handleSendMessage}
              isSending={isSendingMessage}
              disabled={isLoadingMessages}
            />
          </>
        ) : (
          /* Empty No-Conversation State */
          <div className="h-full flex flex-col items-center justify-center text-center p-8 text-slate-400">
            <div className="w-14 h-14 rounded-2xl bg-indigo-50 border border-indigo-100 flex items-center justify-center text-indigo-600 mb-4 shadow-xs">
              <MessagesSquare className="w-7 h-7" />
            </div>
            <h3 className="text-lg font-bold text-slate-800">No Conversation Selected</h3>
            <p className="text-xs text-slate-500 max-w-sm mt-1 mb-6 leading-relaxed">
              Select an existing chat from the left panel or create a new conversation to start asking questions about your indexed documents.
            </p>
            <Button
              onClick={handleCreateConversation}
              leftIcon={<MessagesSquare className="w-4 h-4" />}
            >
              Start New Conversation
            </Button>
          </div>
        )}
      </div>

      {/* Delete Conversation Confirmation Modal */}
      <Modal
        isOpen={!!convToDelete}
        onClose={() => !isDeletingConv && setConvToDelete(null)}
        title="Delete Conversation"
        description="Are you sure you want to delete this conversation? This will delete all messages and citations."
        maxWidth="sm"
      >
        <div className="space-y-4">
          <div className="flex items-start gap-3 p-3 rounded-lg bg-rose-50 border border-rose-200 text-xs text-rose-800">
            <AlertTriangle className="w-5 h-5 text-rose-600 shrink-0 mt-0.5" />
            <p>
              This will permanently delete this conversation and its entire chat history. Documents attached to this conversation will not be deleted.
            </p>
          </div>

          <div className="flex justify-end gap-3 pt-3 border-t border-slate-100">
            <Button
              variant="ghost"
              onClick={() => setConvToDelete(null)}
              disabled={isDeletingConv}
            >
              Cancel
            </Button>
            <Button
              variant="danger"
              onClick={handleDeleteConversationConfirm}
              isLoading={isDeletingConv}
              data-testid="confirm-delete-conv-button"
            >
              Delete
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
};
