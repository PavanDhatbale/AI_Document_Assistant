import React, { useState } from 'react';
import { MessageResponse } from '../../types/conversation';
import { CitationCard } from './CitationCard';
import { MarkdownRenderer } from './MarkdownRenderer';
import { Bot, User, Bookmark, Copy, Check } from 'lucide-react';

interface MessageItemProps {
  message: MessageResponse;
}

export const MessageItem: React.FC<MessageItemProps> = ({ message }) => {
  const isUser = message.role === 'USER';
  const [copied, setCopied] = useState(false);

  const formatTime = (isoString: string): string => {
    try {
      return new Date(isoString).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    } catch {
      return '';
    }
  };

  const handleCopyAnswer = async () => {
    try {
      await navigator.clipboard.writeText(message.content);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Fallback if clipboard API is unavailable
    }
  };

  return (
    <div
      className={`flex gap-3 max-w-4xl w-full ${isUser ? 'ml-auto flex-row-reverse' : 'mr-auto'}`}
      data-testid={`message-${message.id}`}
    >
      {/* Avatar Icon */}
      <div
        className={`w-8 h-8 rounded-xl flex items-center justify-center shrink-0 shadow-2xs mt-1 ${
          isUser
            ? 'bg-indigo-600 text-white'
            : 'bg-white border border-slate-200 text-indigo-600 shadow-xs'
        }`}
      >
        {isUser ? <User className="w-4 h-4" /> : <Bot className="w-4 h-4" />}
      </div>

      {/* Message Body & Citations */}
      <div className={`flex flex-col gap-1.5 max-w-[88%] ${isUser ? 'items-end' : 'items-start'} min-w-0`}>
        {/* Header with Name, Timestamp, and Copy Button */}
        <div className="flex items-center justify-between w-full px-1 text-[11px] text-slate-400">
          <div className="flex items-center gap-2">
            <span className="font-semibold text-slate-600">{isUser ? 'You' : 'DocAssistant AI'}</span>
            <span>{formatTime(message.createdAt)}</span>
          </div>

          {!isUser && (
            <button
              type="button"
              onClick={handleCopyAnswer}
              className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-medium text-slate-500 hover:text-indigo-600 hover:bg-slate-100 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
              title="Copy answer to clipboard"
              aria-label="Copy answer"
              data-testid="copy-answer-button"
            >
              {copied ? (
                <>
                  <Check className="w-3.5 h-3.5 text-emerald-600" />
                  <span className="text-emerald-600 font-semibold">Copied!</span>
                </>
              ) : (
                <>
                  <Copy className="w-3.5 h-3.5" />
                  <span>Copy</span>
                </>
              )}
            </button>
          )}
        </div>

        {/* Message Bubble */}
        <div
          className={`w-full ${
            isUser
              ? 'p-4 rounded-2xl rounded-tr-xs bg-indigo-600 text-white shadow-xs'
              : 'p-5 rounded-2xl rounded-tl-xs bg-white text-slate-800 border border-slate-200/90 shadow-2xs'
          }`}
        >
          {isUser ? (
            <div className="whitespace-pre-wrap font-sans text-[15px] leading-relaxed break-words">
              {message.content}
            </div>
          ) : (
            <MarkdownRenderer
              content={message.content}
              citations={message.citations}
            />
          )}
        </div>

        {/* Citations Panel for Assistant Messages */}
        {!isUser && message.citations && message.citations.length > 0 && (
          <div className="w-full mt-2.5 pt-2.5 border-t border-slate-200/80">
            <div className="flex items-center justify-between mb-2 px-0.5">
              <div className="flex items-center gap-1.5 text-xs font-semibold text-slate-700">
                <Bookmark className="w-3.5 h-3.5 text-indigo-600" />
                <span>Grounded Evidence & Sources ({message.citations.length})</span>
              </div>
              <span className="text-[11px] text-slate-400">Verified document citations</span>
            </div>
            <div className="grid grid-cols-1 gap-2.5 w-full">
              {message.citations.map((citation, index) => (
                <CitationCard key={citation.id} citation={citation} index={index} />
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
