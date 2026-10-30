import React, { useState } from 'react';
import { CitationResponse } from '../../types/conversation';
import { FileText, ChevronDown, ChevronUp, Sparkles } from 'lucide-react';

interface CitationCardProps {
  citation: CitationResponse;
  index?: number;
}

export const CitationCard: React.FC<CitationCardProps> = ({ citation, index }) => {
  const [isExpanded, setIsExpanded] = useState(false);

  // Normalize similarity score to percentage display (e.g. 0.8845 -> 88%)
  const scorePercent = Math.round(citation.relevanceScore * 100);

  // Excerpt length threshold for toggle
  const isLong = citation.excerptText.length > 200;
  const displayedSnippet =
    isExpanded || !isLong
      ? citation.excerptText
      : `${citation.excerptText.substring(0, 200)}...`;

  return (
    <div
      id={`citation-card-${citation.id}`}
      data-citation-index={index !== undefined ? index + 1 : undefined}
      data-testid={`citation-card-${citation.id}`}
      className="p-4 bg-slate-50/80 hover:bg-slate-50/95 rounded-xl border border-slate-200/90 hover:border-indigo-300 shadow-2xs transition-all duration-200 text-sm"
    >
      {/* Header with Document Metadata & Score Badge */}
      <div className="flex flex-wrap items-center justify-between gap-2 mb-2.5">
        <div className="flex items-center gap-2 min-w-0 flex-wrap">
          <FileText className="w-4 h-4 text-indigo-600 shrink-0" />
          <span
            className="font-semibold text-slate-900 text-sm truncate max-w-[200px] sm:max-w-xs"
            title={citation.documentName}
          >
            {citation.documentName}
          </span>
          <span className="text-slate-300 shrink-0">•</span>
          <span className="inline-flex items-center px-2 py-0.5 rounded-md bg-white border border-slate-200 text-xs font-medium text-slate-700 shrink-0 shadow-2xs">
            Page {citation.pageNumber}
          </span>
          <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[11px] font-mono text-slate-500 bg-slate-100/70 shrink-0">
            #chunk-{citation.chunkIndex}
          </span>
        </div>

        {/* Relevance Score Pill */}
        <span
          className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-emerald-50 text-emerald-700 border border-emerald-200 shrink-0 shadow-2xs"
          title={`Cosine similarity match: ${citation.relevanceScore.toFixed(4)}`}
        >
          <Sparkles className="w-3 h-3 text-emerald-600" />
          {scorePercent}% match
        </span>
      </div>

      {/* Proportional Font Excerpt Text (14px, leading-1.6) */}
      <div className="bg-white p-3.5 rounded-lg border border-slate-200/70 shadow-2xs font-sans text-sm leading-[1.6] text-slate-700 whitespace-pre-wrap break-words">
        &ldquo;{displayedSnippet}&rdquo;
      </div>

      {/* Accessible Expand/Collapse Toggle */}
      {isLong && (
        <button
          type="button"
          onClick={() => setIsExpanded((prev) => !prev)}
          className="mt-2 inline-flex items-center gap-1 text-xs font-semibold text-indigo-600 hover:text-indigo-800 transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 rounded px-1.5 py-0.5"
          aria-expanded={isExpanded}
        >
          {isExpanded ? (
            <>
              <ChevronUp className="w-3.5 h-3.5" />
              <span>Show less</span>
            </>
          ) : (
            <>
              <ChevronDown className="w-3.5 h-3.5" />
              <span>Show full excerpt</span>
            </>
          )}
        </button>
      )}
    </div>
  );
};
