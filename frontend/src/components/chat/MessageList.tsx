import React, { useEffect, useRef } from 'react';
import { MessageResponse } from '../../types/conversation';
import { MessageItem } from './MessageItem';
import { Bot, Sparkles } from 'lucide-react';

interface MessageListProps {
  messages: MessageResponse[];
  isWaitingForAnswer: boolean;
}

export const MessageList: React.FC<MessageListProps> = ({
  messages,
  isWaitingForAnswer,
}) => {
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, isWaitingForAnswer]);

  return (
    <div className="flex-1 overflow-y-auto p-4 sm:p-6 pb-12 sm:pb-16 space-y-6">
      {messages.length === 0 ? (
        <div className="h-full min-h-[320px] flex flex-col items-center justify-center text-center p-8 text-slate-400">
          <div className="w-12 h-12 rounded-2xl bg-indigo-50 border border-indigo-100 flex items-center justify-center text-indigo-600 mb-3 shadow-xs">
            <Sparkles className="w-6 h-6" />
          </div>
          <h3 className="text-base font-semibold text-slate-800">
            Start a grounded conversation
          </h3>
          <p className="text-xs text-slate-500 max-w-sm mt-1 leading-relaxed">
            Ask any question about your indexed documents. Answers are strictly grounded in retrieved excerpts with verified page numbers.
          </p>
        </div>
      ) : (
        messages.map((msg) => <MessageItem key={msg.id} message={msg} />)
      )}

      {/* Assistant Thinking / Generating Indicator */}
      {isWaitingForAnswer && (
        <div
          className="flex gap-3 max-w-4xl mr-auto animate-in fade-in"
          data-testid="assistant-thinking-indicator"
        >
          <div className="w-8 h-8 rounded-xl bg-white border border-slate-200 text-indigo-600 flex items-center justify-center shadow-xs shrink-0 mt-1">
            <Bot className="w-4 h-4 animate-pulse" />
          </div>
          <div className="flex flex-col gap-1.5 items-start">
            <div className="flex items-center gap-2 px-1 text-[11px] text-slate-400">
              <span className="font-semibold text-slate-700">DocAssistant AI</span>
              <span className="text-indigo-600 font-medium">Retrieving evidence & generating answer...</span>
            </div>
            <div className="p-4 rounded-2xl rounded-tl-xs bg-white border border-slate-200/90 shadow-2xs">
              <div className="flex items-center gap-2">
                <span className="w-2.5 h-2.5 rounded-full bg-indigo-600 animate-bounce" style={{ animationDelay: '0ms' }} />
                <span className="w-2.5 h-2.5 rounded-full bg-indigo-600 animate-bounce" style={{ animationDelay: '150ms' }} />
                <span className="w-2.5 h-2.5 rounded-full bg-indigo-600 animate-bounce" style={{ animationDelay: '300ms' }} />
              </div>
            </div>
          </div>
        </div>
      )}

      <div ref={bottomRef} />
    </div>
  );
};
