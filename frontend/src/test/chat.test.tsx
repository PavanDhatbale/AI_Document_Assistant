import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MessageComposer } from '../components/chat/MessageComposer';
import { CitationCard } from '../components/chat/CitationCard';
import { MessageItem } from '../components/chat/MessageItem';
import { MarkdownRenderer } from '../components/chat/MarkdownRenderer';
import { CitationResponse, MessageResponse } from '../types/conversation';
import { ConversationsPage } from '../pages/ConversationsPage';
import { conversationsApi } from '../api/conversations';
import { documentsApi } from '../api/documents';
import { ToastProvider } from '../context/ToastContext';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../api/conversations', () => ({
  conversationsApi: {
    listConversations: vi.fn(),
    getConversation: vi.fn(),
    createConversation: vi.fn(),
    deleteConversation: vi.fn(),
    sendMessage: vi.fn(),
    addDocumentsToConversation: vi.fn(),
    removeDocumentFromConversation: vi.fn(),
  },
}));

vi.mock('../api/documents', () => ({
  documentsApi: {
    listDocuments: vi.fn(),
  },
}));

describe('Chat Pipeline, Citations, and Error Handling', () => {
  beforeEach(() => {
    // Mock navigator.clipboard
    Object.assign(navigator, {
      clipboard: {
        writeText: vi.fn().mockResolvedValue(undefined),
      },
    });
  });

  // 9. Preventing chat submission of blank messages
  it('disables send button when composer input is blank or whitespace', () => {
    const onSend = vi.fn();

    render(<MessageComposer onSend={onSend} isSending={false} />);

    const input = screen.getByTestId('chat-message-input');
    const sendBtn = screen.getByTestId('chat-send-button');

    // Initially blank -> disabled
    expect(sendBtn).toBeDisabled();

    // Whitespace only -> disabled
    fireEvent.change(input, { target: { value: '    ' } });
    expect(sendBtn).toBeDisabled();

    // Valid text -> enabled
    fireEvent.change(input, { target: { value: 'What is the Q3 revenue?' } });
    expect(sendBtn).toBeEnabled();

    // Trigger form submit
    fireEvent.click(sendBtn);
    expect(onSend).toHaveBeenCalledWith('What is the Q3 revenue?');
  });

  // 10. Rendering citations returned by the backend
  it('renders citation metadata accurately and toggles full excerpt expansion', () => {
    const mockCitation: CitationResponse = {
      id: 'cit-1',
      documentId: 'doc-1',
      documentName: 'annual_report.pdf',
      pageNumber: 12,
      chunkIndex: 3,
      relevanceScore: 0.8845,
      excerptText:
        'The consolidated operating revenue increased by 18.4% year-over-year, reaching 420 million dollars across all enterprise divisions including international subsidiaries and domestic distribution networks.',
      createdAt: '2026-10-24T12:00:00Z',
    };

    render(<CitationCard citation={mockCitation} />);

    expect(screen.getByText('annual_report.pdf')).toBeInTheDocument();
    expect(screen.getByText('Page 12')).toBeInTheDocument();
    expect(screen.getByText('#chunk-3')).toBeInTheDocument();
    expect(screen.getByText('88% match')).toBeInTheDocument();

    // Verify toggle expansion button exists for long text
    const toggleBtn = screen.getByText('Show full excerpt');
    expect(toggleBtn).toBeInTheDocument();

    fireEvent.click(toggleBtn);
    expect(screen.getByText('Show less')).toBeInTheDocument();
  });

  it('renders assistant message with grounding citations section', () => {
    const assistantMsg: MessageResponse = {
      id: 'msg-assistant-1',
      role: 'ASSISTANT',
      content: 'Operating revenue reached $420M [Doc: doc-1, Page: 12, Chunk: 3].',
      tokenCount: 45,
      createdAt: '2026-10-24T12:01:00Z',
      citations: [
        {
          id: 'cit-10',
          documentId: 'doc-1',
          documentName: 'annual_report.pdf',
          pageNumber: 12,
          chunkIndex: 3,
          relevanceScore: 0.88,
          excerptText: 'Operating revenue was $420 million...',
          createdAt: '2026-10-24T12:01:00Z',
        },
      ],
    };

    render(<MessageItem message={assistantMsg} />);

    expect(screen.getByText(/Operating revenue reached \$420M/)).toBeInTheDocument();
    expect(screen.getByText(/Grounded Evidence & Sources \(1\)/)).toBeInTheDocument();
    expect(screen.getByText('annual_report.pdf')).toBeInTheDocument();
  });

  it('renders insufficient-evidence response without citation cards or sources heading', () => {
    const insufficientMsg: MessageResponse = {
      id: 'msg-assistant-fallback',
      role: 'ASSISTANT',
      content:
        'I cannot find sufficient information in the provided documents to answer your question.',
      tokenCount: 22,
      createdAt: '2026-10-24T12:02:00Z',
      citations: [],
    };

    render(<MessageItem message={insufficientMsg} />);

    expect(
      screen.getByText(
        'I cannot find sufficient information in the provided documents to answer your question.'
      )
    ).toBeInTheDocument();
    expect(
      screen.queryByText(/Grounded Evidence & Sources/)
    ).not.toBeInTheDocument();
  });

  // 11. Handling a conversation API failure
  it('handles message API failure gracefully and preserves user message draft', async () => {
    const onSendMock = vi.fn().mockResolvedValue(false); // Returning false signals failure

    render(<MessageComposer onSend={onSendMock} isSending={false} />);

    const input = screen.getByTestId('chat-message-input');
    const sendBtn = screen.getByTestId('chat-send-button');

    fireEvent.change(input, { target: { value: 'Why did Q3 profit drop?' } });
    fireEvent.click(sendBtn);

    await waitFor(() => {
      expect(onSendMock).toHaveBeenCalledWith('Why did Q3 profit drop?');
    });

    // Message draft remains in input so user doesn't lose text
    expect(input).toHaveValue('Why did Q3 profit drop?');
  });

  it('displays error notification when conversation loading fails', async () => {
    (conversationsApi.listConversations as any).mockRejectedValueOnce(
      new Error('Failed to load')
    );
    (documentsApi.listDocuments as any).mockResolvedValueOnce([]);

    render(
      <MemoryRouter>
        <ToastProvider>
          <ConversationsPage />
        </ToastProvider>
      </MemoryRouter>
    );

    expect(await screen.findByText('Failed to load conversations.')).toBeInTheDocument();
  });

  // UI & Typography Enhancements
  it('renders AI response markdown elements with proper structure and typography', () => {
    const markdownContent = `
## Summary of SQL Queries
According to the indexed documents, SQL statements can be categorized:
- **DDL**: Data Definition Language
- **DML**: Data Manipulation Language

\`\`\`sql
SELECT * FROM users WHERE active = true;
\`\`\`

Refer to [Source 1] for primary definitions.
`;

    render(<MarkdownRenderer content={markdownContent} />);

    // Check heading
    expect(screen.getByRole('heading', { level: 2, name: 'Summary of SQL Queries' })).toBeInTheDocument();
    // Check bold list item
    expect(screen.getByText('DDL')).toBeInTheDocument();
    // Check code snippet
    expect(screen.getByText(/SELECT \* FROM users/)).toBeInTheDocument();
    // Check interactive citation tag
    expect(screen.getByTestId('inline-citation-tag')).toBeInTheDocument();
    expect(screen.getByText('Source 1')).toBeInTheDocument();
  });

  it('copies AI response to clipboard when copy button is clicked', async () => {
    const assistantMsg: MessageResponse = {
      id: 'msg-assistant-copy',
      role: 'ASSISTANT',
      content: 'This is an answer that can be copied.',
      tokenCount: 15,
      createdAt: '2026-10-24T12:05:00Z',
      citations: [],
    };

    render(<MessageItem message={assistantMsg} />);

    const copyBtn = screen.getByTestId('copy-answer-button');
    expect(copyBtn).toBeInTheDocument();

    fireEvent.click(copyBtn);

    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('This is an answer that can be copied.');
    expect(await screen.findByText('Copied!')).toBeInTheDocument();
  });

  it('scrolls to citation card when inline citation tag is clicked', () => {
    const scrollIntoViewMock = vi.fn();
    window.HTMLElement.prototype.scrollIntoView = scrollIntoViewMock;

    const assistantMsg: MessageResponse = {
      id: 'msg-with-citations',
      role: 'ASSISTANT',
      content: 'Database indexing significantly improves query speed [Source 1].',
      tokenCount: 20,
      createdAt: '2026-10-24T12:06:00Z',
      citations: [
        {
          id: 'cit-speed-1',
          documentId: 'doc-speed',
          documentName: 'database_guide.pdf',
          pageNumber: 5,
          chunkIndex: 2,
          relevanceScore: 0.94,
          excerptText: 'Indexes allow relational databases to locate records without full table scans.',
          createdAt: '2026-10-24T12:06:00Z',
        },
      ],
    };

    render(<MessageItem message={assistantMsg} />);

    const inlineTag = screen.getByTestId('inline-citation-tag');
    expect(inlineTag).toBeInTheDocument();

    fireEvent.click(inlineTag);

    expect(scrollIntoViewMock).toHaveBeenCalled();
  });
});
