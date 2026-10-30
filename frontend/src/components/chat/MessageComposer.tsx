import React, { useState, useRef, useEffect } from 'react';
import { Send, CornerDownLeft } from 'lucide-react';
import { Button } from '../common/Button';

interface MessageComposerProps {
  onSend: (content: string) => Promise<boolean>;
  isSending: boolean;
  disabled?: boolean;
}

const MAX_MESSAGE_LENGTH = 4000;

export const MessageComposer: React.FC<MessageComposerProps> = ({
  onSend,
  isSending,
  disabled = false,
}) => {
  const [content, setContent] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (!isSending && !disabled) {
      textareaRef.current?.focus();
    }
  }, [isSending, disabled]);

  const trimmedContent = content.trim();
  const isTooLong = content.length > MAX_MESSAGE_LENGTH;
  const canSubmit = trimmedContent.length > 0 && !isTooLong && !isSending && !disabled;

  const handleSubmit = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    if (!canSubmit) return;

    const messageText = trimmedContent;
    // Call onSend: if successful, clear content; if failed, retain content so user doesn't lose text
    const success = await onSend(messageText);
    if (success) {
      setContent('');
      if (textareaRef.current) {
        textareaRef.current.style.height = 'auto';
      }
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit();
    }
  };

  const handleChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setContent(e.target.value);
    // Auto-adjust height
    e.target.style.height = 'auto';
    e.target.style.height = `${Math.min(e.target.scrollHeight, 180)}px`;
  };

  return (
    <form
      onSubmit={handleSubmit}
      className="p-4 bg-white border-t border-slate-200/80 shadow-xs"
      data-testid="message-composer-form"
    >
      <div className="relative rounded-2xl border border-slate-200/90 focus-within:border-indigo-600 focus-within:ring-2 focus-within:ring-indigo-100 transition-all bg-white shadow-2xs">
        <textarea
          ref={textareaRef}
          value={content}
          onChange={handleChange}
          onKeyDown={handleKeyDown}
          placeholder={
            disabled
              ? 'Select or create a conversation to begin...'
              : 'Ask a question about your documents... (Shift+Enter for new line)'
          }
          disabled={disabled || isSending}
          rows={2}
          className="w-full resize-none px-4 pt-3 pb-10 text-sm text-slate-800 placeholder-slate-400 bg-transparent focus:outline-none disabled:opacity-60 disabled:cursor-not-allowed"
          data-testid="chat-message-input"
          aria-label="Ask a question about your documents"
        />

        {/* Footer controls inside textarea container */}
        <div className="absolute bottom-2 left-4 right-3 flex items-center justify-between text-xs text-slate-400">
          <div className="flex items-center gap-2">
            <span
              className={`text-[11px] font-medium ${
                isTooLong ? 'text-rose-600 font-semibold' : 'text-slate-400'
              }`}
            >
              {content.length}/{MAX_MESSAGE_LENGTH}
            </span>
            <span className="hidden sm:inline text-slate-300">•</span>
            <span className="hidden sm:inline text-[11px] text-slate-400">
              Press <CornerDownLeft className="inline w-3 h-3 text-slate-400" /> to send
            </span>
          </div>

          <Button
            type="submit"
            size="sm"
            disabled={!canSubmit}
            isLoading={isSending}
            leftIcon={<Send className="w-3.5 h-3.5" />}
            data-testid="chat-send-button"
            aria-label="Send message"
          >
            Ask AI
          </Button>
        </div>
      </div>
    </form>
  );
};
