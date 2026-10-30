import React from 'react';
import { ConversationResponse } from '../../types/conversation';
import { MessagesSquare, Plus, Trash2 } from 'lucide-react';
import { Button } from '../common/Button';

interface ConversationListProps {
  conversations: ConversationResponse[];
  activeConversationId: string | null;
  onSelectConversation: (id: string) => void;
  onCreateConversation: () => void;
  onDeleteConversation: (id: string, e: React.MouseEvent) => void;
  isLoading: boolean;
}

export const ConversationList: React.FC<ConversationListProps> = ({
  conversations,
  activeConversationId,
  onSelectConversation,
  onCreateConversation,
  onDeleteConversation,
  isLoading,
}) => {
  const formatDate = (isoString: string): string => {
    try {
      const date = new Date(isoString);
      const now = new Date();
      if (date.toDateString() === now.toDateString()) {
        return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
      }
      return date.toLocaleDateString([], { month: 'short', day: 'numeric' });
    } catch {
      return '';
    }
  };

  return (
    <div className="w-full h-full flex flex-col bg-slate-50 border-r border-slate-200/80">
      {/* Header with New Chat Button */}
      <div className="p-3 border-b border-slate-200/80">
        <Button
          onClick={onCreateConversation}
          variant="primary"
          size="sm"
          className="w-full justify-center"
          leftIcon={<Plus className="w-4 h-4" />}
          data-testid="new-conversation-button"
        >
          New Conversation
        </Button>
      </div>

      {/* Conversations Scrollable List */}
      <div className="flex-1 overflow-y-auto p-2 space-y-1">
        {isLoading ? (
          <div className="space-y-2 p-2 animate-pulse">
            {[1, 2, 3, 4].map((n) => (
              <div key={n} className="h-14 bg-slate-200/70 rounded-xl" />
            ))}
          </div>
        ) : conversations.length === 0 ? (
          <div className="text-center py-8 px-4 text-xs text-slate-400">
            <MessagesSquare className="w-8 h-8 mx-auto mb-2 text-slate-300" />
            <p>No conversations yet.</p>
            <p className="mt-1 text-slate-500">Create one to start asking questions.</p>
          </div>
        ) : (
          conversations.map((conv) => {
            const isActive = conv.id === activeConversationId;
            return (
              <div
                key={conv.id}
                onClick={() => onSelectConversation(conv.id)}
                className={`group flex items-center justify-between p-3 rounded-xl cursor-pointer text-xs transition-all ${
                  isActive
                    ? 'bg-indigo-600 text-white shadow-xs'
                    : 'hover:bg-slate-200/60 text-slate-700'
                }`}
                data-testid={`conversation-item-${conv.id}`}
              >
                <div className="min-w-0 pr-2 flex-1">
                  <h4
                    className={`font-semibold truncate ${
                      isActive ? 'text-white' : 'text-slate-800'
                    }`}
                  >
                    {conv.title || 'Untitled Conversation'}
                  </h4>
                  <p
                    className={`text-[11px] mt-0.5 ${
                      isActive ? 'text-indigo-200' : 'text-slate-400'
                    }`}
                  >
                    {formatDate(conv.updatedAt)} • {conv.messageCount} messages
                  </p>
                </div>

                <button
                  onClick={(e) => onDeleteConversation(conv.id, e)}
                  className={`opacity-0 group-hover:opacity-100 p-1.5 rounded-md transition focus:opacity-100 ${
                    isActive
                      ? 'text-indigo-200 hover:text-white hover:bg-indigo-700'
                      : 'text-slate-400 hover:text-rose-600 hover:bg-rose-50'
                  }`}
                  aria-label={`Delete conversation ${conv.title}`}
                  data-testid={`delete-conversation-${conv.id}`}
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
};
