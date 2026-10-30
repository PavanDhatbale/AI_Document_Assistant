import React, { useState } from 'react';
import { CitationResponse } from '../../types/conversation';
import { Bookmark, Copy, Check } from 'lucide-react';

interface MarkdownRendererProps {
  content: string;
  citations?: CitationResponse[];
  className?: string;
}

interface CodeBlockProps {
  code: string;
  language?: string;
}

const CodeBlock: React.FC<CodeBlockProps> = ({ code, language }) => {
  const [copied, setCopied] = useState(false);

  const handleCopyCode = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Fallback if clipboard API not permitted
    }
  };

  return (
    <div className="my-3.5 rounded-xl overflow-hidden border border-slate-700/80 bg-slate-900 text-slate-100 shadow-sm">
      <div className="flex items-center justify-between px-3.5 py-1.5 bg-slate-800/90 border-b border-slate-700/70 text-xs text-slate-300">
        <span className="font-mono text-[11px] font-semibold tracking-wider uppercase text-slate-400">
          {language || 'Code'}
        </span>
        <button
          type="button"
          onClick={handleCopyCode}
          className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[11px] font-medium text-slate-300 hover:text-white hover:bg-slate-700 transition-colors focus-visible:ring-2 focus-visible:ring-indigo-400 focus-visible:outline-none"
          title="Copy code snippet"
          aria-label="Copy code snippet"
        >
          {copied ? (
            <>
              <Check className="w-3 h-3 text-emerald-400" />
              <span className="text-emerald-400 font-semibold">Copied!</span>
            </>
          ) : (
            <>
              <Copy className="w-3 h-3" />
              <span>Copy</span>
            </>
          )}
        </button>
      </div>
      <pre className="p-4 overflow-x-auto text-[13px] font-mono leading-relaxed text-slate-100">
        <code>{code}</code>
      </pre>
    </div>
  );
};

export const MarkdownRenderer: React.FC<MarkdownRendererProps> = ({
  content,
  citations = [],
  className = '',
}) => {
  const handleCitationClick = (sourceNum: number) => {
    // 1. Try matching by data-citation-index (1-based index)
    const byIndex = document.querySelector(`[data-citation-index="${sourceNum}"]`);
    let target = byIndex;

    // 2. Fallback to citation-card ID
    if (!target && citations[sourceNum - 1]) {
      target = document.getElementById(`citation-card-${citations[sourceNum - 1].id}`);
    }

    // 3. Fallback to any citation card
    if (!target) {
      target = document.querySelector(`[data-testid^="citation-card-"]`);
    }

    if (target) {
      target.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      target.classList.add('ring-2', 'ring-indigo-500', 'bg-indigo-50/70');
      setTimeout(() => {
        target?.classList.remove('ring-2', 'ring-indigo-500', 'bg-indigo-50/70');
      }, 2000);
    }
  };

  const handleDocCitationClick = (docName: string, pageNum: number) => {
    const cards = document.querySelectorAll(`[data-testid^="citation-card-"]`);
    for (const card of Array.from(cards)) {
      if (
        card.textContent?.includes(`Page ${pageNum}`) &&
        (!docName || card.textContent?.includes(docName))
      ) {
        card.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        card.classList.add('ring-2', 'ring-indigo-500', 'bg-indigo-50/70');
        setTimeout(() => {
          card.classList.remove('ring-2', 'ring-indigo-500', 'bg-indigo-50/70');
        }, 2000);
        return;
      }
    }
  };

  const renderInline = (text: string): React.ReactNode[] => {
    const pattern =
      /(\[Source\s+(\d+)\]|\[Doc:\s*([^,\]]+),\s*Page:\s*(\d+)(?:,\s*Chunk:\s*(\d+))?\]|`([^`]+)`|\*\*([^*]+)\*\*|\*([^*]+)\*|\[([^\]]+)\]\((https?:\/\/[^\s)]+)\))/g;

    const nodes: React.ReactNode[] = [];
    let lastIndex = 0;
    let match: RegExpExecArray | null;

    while ((match = pattern.exec(text)) !== null) {
      if (match.index > lastIndex) {
        nodes.push(text.substring(lastIndex, match.index));
      }

      const fullMatch = match[0];

      if (fullMatch.startsWith('[Source')) {
        const sourceNum = parseInt(match[2], 10);
        nodes.push(
          <button
            key={`source-${match.index}`}
            type="button"
            onClick={() => handleCitationClick(sourceNum)}
            className="inline-flex items-center gap-1 mx-1 px-1.5 py-0.5 rounded text-xs font-semibold bg-indigo-50 text-indigo-700 hover:bg-indigo-100 hover:border-indigo-300 border border-indigo-200 transition-colors cursor-pointer select-none align-baseline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
            title={`Jump to Source ${sourceNum}`}
            data-testid="inline-citation-tag"
          >
            <Bookmark className="w-3 h-3 text-indigo-600 shrink-0" />
            <span>Source {sourceNum}</span>
          </button>
        );
      } else if (fullMatch.startsWith('[Doc:')) {
        const docName = match[3];
        const pageNum = parseInt(match[4], 10);
        nodes.push(
          <button
            key={`doc-${match.index}`}
            type="button"
            onClick={() => handleDocCitationClick(docName, pageNum)}
            className="inline-flex items-center gap-1 mx-1 px-1.5 py-0.5 rounded text-xs font-semibold bg-indigo-50 text-indigo-700 hover:bg-indigo-100 hover:border-indigo-300 border border-indigo-200 transition-colors cursor-pointer select-none align-baseline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
            title={`Jump to ${docName} Page ${pageNum}`}
            data-testid="inline-citation-tag"
          >
            <Bookmark className="w-3 h-3 text-indigo-600 shrink-0" />
            <span>
              Doc: {docName}, Page {pageNum}
            </span>
          </button>
        );
      } else if (fullMatch.startsWith('`')) {
        const codeContent = match[6];
        nodes.push(
          <code
            key={`code-${match.index}`}
            className="px-1.5 py-0.5 rounded text-[13px] font-mono bg-slate-100 text-slate-800 border border-slate-200/80 font-medium"
          >
            {codeContent}
          </code>
        );
      } else if (fullMatch.startsWith('**')) {
        const boldContent = match[7];
        nodes.push(
          <strong key={`bold-${match.index}`} className="font-semibold text-slate-900">
            {boldContent}
          </strong>
        );
      } else if (fullMatch.startsWith('*')) {
        const italicContent = match[8];
        nodes.push(
          <em key={`italic-${match.index}`} className="italic text-slate-800">
            {italicContent}
          </em>
        );
      } else if (match[9] && match[10]) {
        const label = match[9];
        const url = match[10];
        nodes.push(
          <a
            key={`link-${match.index}`}
            href={url}
            target="_blank"
            rel="noopener noreferrer"
            className="text-indigo-600 hover:text-indigo-800 underline underline-offset-2 transition-colors"
          >
            {label}
          </a>
        );
      }

      lastIndex = pattern.lastIndex;
    }

    if (lastIndex < text.length) {
      nodes.push(text.substring(lastIndex));
    }

    return nodes;
  };

  // Block level parser
  const renderBlocks = (): React.ReactNode[] => {
    const rawLines = content.split(/\r?\n/);
    const elements: React.ReactNode[] = [];
    let i = 0;

    while (i < rawLines.length) {
      const line = rawLines[i];
      const trimmed = line.trim();

      // 1. Code Block
      if (trimmed.startsWith('```')) {
        const language = trimmed.slice(3).trim();
        const codeLines: string[] = [];
        i++;
        while (i < rawLines.length && !rawLines[i].trim().startsWith('```')) {
          codeLines.push(rawLines[i]);
          i++;
        }
        if (i < rawLines.length) i++; // Skip closing ```
        elements.push(
          <CodeBlock
            key={`codeblock-${i}`}
            language={language}
            code={codeLines.join('\n')}
          />
        );
        continue;
      }

      // 2. Table Block (lines starting with | and next line has |---)
      if (
        trimmed.startsWith('|') &&
        i + 1 < rawLines.length &&
        rawLines[i + 1].includes('|-')
      ) {
        const tableLines: string[] = [];
        while (i < rawLines.length && rawLines[i].trim().startsWith('|')) {
          tableLines.push(rawLines[i]);
          i++;
        }
        const parseRow = (rowStr: string) =>
          rowStr
            .trim()
            .replace(/^\|/, '')
            .replace(/\|$/, '')
            .split('|')
            .map((c) => c.trim());

        const headers = parseRow(tableLines[0]);
        const bodyRows = tableLines.slice(2).map(parseRow);

        elements.push(
          <div
            key={`table-${i}`}
            className="overflow-x-auto my-3.5 border border-slate-200/90 rounded-xl shadow-2xs"
          >
            <table className="min-w-full divide-y divide-slate-200 text-sm">
              <thead className="bg-slate-50">
                <tr>
                  {headers.map((h, colIdx) => (
                    <th
                      key={colIdx}
                      className="px-3.5 py-2.5 text-left font-semibold text-slate-800 text-xs tracking-wider"
                    >
                      {renderInline(h)}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 bg-white">
                {bodyRows.map((row, rIdx) => (
                  <tr
                    key={rIdx}
                    className={rIdx % 2 === 0 ? 'bg-white' : 'bg-slate-50/50'}
                  >
                    {row.map((cell, cIdx) => (
                      <td key={cIdx} className="px-3.5 py-2 text-slate-700 text-xs">
                        {renderInline(cell)}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        );
        continue;
      }

      // 3. Headings
      if (line.startsWith('#### ')) {
        elements.push(
          <h4
            key={`h4-${i}`}
            className="text-sm font-semibold text-slate-800 mt-2.5 mb-1"
          >
            {renderInline(line.slice(5))}
          </h4>
        );
        i++;
        continue;
      }
      if (line.startsWith('### ')) {
        elements.push(
          <h3
            key={`h3-${i}`}
            className="text-base font-semibold text-slate-800 mt-3 mb-1.5"
          >
            {renderInline(line.slice(4))}
          </h3>
        );
        i++;
        continue;
      }
      if (line.startsWith('## ')) {
        elements.push(
          <h2
            key={`h2-${i}`}
            className="text-lg font-semibold text-slate-900 mt-3.5 mb-2"
          >
            {renderInline(line.slice(3))}
          </h2>
        );
        i++;
        continue;
      }
      if (line.startsWith('# ')) {
        elements.push(
          <h1
            key={`h1-${i}`}
            className="text-xl font-bold text-slate-900 mt-4 mb-2.5"
          >
            {renderInline(line.slice(2))}
          </h1>
        );
        i++;
        continue;
      }

      // 4. Blockquotes
      if (line.startsWith('> ') || line === '>') {
        const quoteLines: string[] = [];
        while (
          i < rawLines.length &&
          (rawLines[i].startsWith('> ') || rawLines[i] === '>')
        ) {
          quoteLines.push(rawLines[i].replace(/^>\s?/, ''));
          i++;
        }
        elements.push(
          <blockquote
            key={`quote-${i}`}
            className="border-l-4 border-indigo-400 pl-4 py-1.5 my-3 text-slate-600 italic bg-slate-50/70 rounded-r text-[15px]"
          >
            {quoteLines.map((ql, qIdx) => (
              <p key={qIdx} className={qIdx > 0 ? 'mt-1' : ''}>
                {renderInline(ql)}
              </p>
            ))}
          </blockquote>
        );
        continue;
      }

      // 5. Unordered List
      if (/^(\*|-|\+)\s+/.test(line)) {
        const listItems: string[] = [];
        while (i < rawLines.length && /^(\*|-|\+)\s+/.test(rawLines[i])) {
          listItems.push(rawLines[i].replace(/^(\*|-|\+)\s+/, ''));
          i++;
        }
        elements.push(
          <ul
            key={`ul-${i}`}
            className="list-disc pl-5 my-2.5 space-y-1.5 text-slate-800 leading-[1.7]"
          >
            {listItems.map((item, itemIdx) => (
              <li key={itemIdx}>{renderInline(item)}</li>
            ))}
          </ul>
        );
        continue;
      }

      // 6. Ordered List
      if (/^\d+\.\s+/.test(line)) {
        const listItems: string[] = [];
        while (i < rawLines.length && /^\d+\.\s+/.test(rawLines[i])) {
          listItems.push(rawLines[i].replace(/^\d+\.\s+/, ''));
          i++;
        }
        elements.push(
          <ol
            key={`ol-${i}`}
            className="list-decimal pl-5 my-2.5 space-y-1.5 text-slate-800 leading-[1.7]"
          >
            {listItems.map((item, itemIdx) => (
              <li key={itemIdx}>{renderInline(item)}</li>
            ))}
          </ol>
        );
        continue;
      }

      // 7. Blank lines
      if (trimmed === '') {
        i++;
        continue;
      }

      // 8. Paragraphs
      const paraLines: string[] = [];
      while (
        i < rawLines.length &&
        rawLines[i].trim() !== '' &&
        !rawLines[i].trim().startsWith('```') &&
        !rawLines[i].trim().startsWith('#') &&
        !rawLines[i].trim().startsWith('>') &&
        !/^(\*|-|\+)\s+/.test(rawLines[i]) &&
        !/^\d+\.\s+/.test(rawLines[i]) &&
        !(
          rawLines[i].trim().startsWith('|') &&
          i + 1 < rawLines.length &&
          rawLines[i + 1].includes('|-')
        )
      ) {
        paraLines.push(rawLines[i]);
        i++;
      }

      elements.push(
        <p
          key={`para-${i}`}
          className="mb-3 last:mb-0 leading-[1.7] text-slate-800 break-words"
        >
          {renderInline(paraLines.join('\n'))}
        </p>
      );
    }

    return elements;
  };

  return (
    <div
      className={`markdown-content text-base leading-[1.7] text-slate-800 font-sans ${className}`}
      data-testid="ai-markdown-content"
    >
      {renderBlocks()}
    </div>
  );
};
