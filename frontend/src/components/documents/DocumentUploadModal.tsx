import React, { useState, useRef } from 'react';
import { Modal } from '../common/Modal';
import { Button } from '../common/Button';
import { documentsApi } from '../../api/documents';
import { useToast } from '../../context/ToastContext';
import { UploadCloud, FileText, AlertCircle, CheckCircle } from 'lucide-react';
import { DocumentResponse } from '../../types/document';

interface DocumentUploadModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: (document: DocumentResponse) => void;
}

const MAX_FILE_SIZE_BYTES = Number(import.meta.env.VITE_MAX_FILE_SIZE_BYTES) || 26214400; // 25 MB

export const DocumentUploadModal: React.FC<DocumentUploadModalProps> = ({
  isOpen,
  onClose,
  onSuccess,
}) => {
  const [file, setFile] = useState<File | null>(null);
  const [isDragging, setIsDragging] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isUploading, setIsUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [autoProcess, setAutoProcess] = useState(true);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const { addToast } = useToast();

  const validateAndSetFile = (selectedFile: File) => {
    setError(null);

    // Validate MIME type or file extension
    const isPdf =
      selectedFile.type === 'application/pdf' ||
      selectedFile.name.toLowerCase().endsWith('.pdf');

    if (!isPdf) {
      setError('Only PDF documents (.pdf) are supported.');
      return;
    }

    if (selectedFile.size > MAX_FILE_SIZE_BYTES) {
      const maxMb = Math.round(MAX_FILE_SIZE_BYTES / (1024 * 1024));
      setError(`File size exceeds maximum permitted limit (${maxMb}MB).`);
      return;
    }

    if (selectedFile.size === 0) {
      setError('The selected file is empty.');
      return;
    }

    setFile(selectedFile);
  };

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(true);
  };

  const handleDragLeave = () => {
    setIsDragging(false);
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      validateAndSetFile(e.dataTransfer.files[0]);
    }
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files.length > 0) {
      validateAndSetFile(e.target.files[0]);
    }
  };

  const handleUpload = async () => {
    if (!file) return;

    setIsUploading(true);
    setUploadProgress(0);
    setError(null);

    try {
      const uploadedDoc = await documentsApi.uploadDocument(file, (progress) => {
        if (progress.total) {
          const percent = Math.round((progress.loaded * 100) / progress.total);
          setUploadProgress(percent);
        }
      });

      addToast(`Document "${file.name}" uploaded successfully.`, 'success');

      // If auto-process is checked, trigger text extraction & embedding
      if (autoProcess) {
        try {
          const processedDoc = await documentsApi.processDocument(uploadedDoc.id);
          addToast(`Started text extraction & embedding for "${file.name}".`, 'info');
          onSuccess(processedDoc);
        } catch {
          // If auto-process trigger fails, still retain the uploaded doc
          onSuccess(uploadedDoc);
        }
      } else {
        onSuccess(uploadedDoc);
      }

      handleClose();
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      const msg = apiErr.message || 'Failed to upload document. Please check the file and try again.';
      setError(msg);
      addToast(msg, 'error');
    } finally {
      setIsUploading(false);
    }
  };

  const handleClose = () => {
    setFile(null);
    setError(null);
    setUploadProgress(0);
    setIsUploading(false);
    onClose();
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={isUploading ? () => {} : handleClose}
      title="Upload Document"
      description="Upload PDF documents to parse, embed, and query with AI assistance."
      maxWidth="md"
    >
      <div className="space-y-4">
        {/* Drag & Drop Area */}
        <div
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onDrop={handleDrop}
          onClick={() => !isUploading && fileInputRef.current?.click()}
          className={`border-2 border-dashed rounded-xl p-8 flex flex-col items-center justify-center text-center cursor-pointer transition-colors ${
            isDragging
              ? 'border-indigo-500 bg-indigo-50/50'
              : file
              ? 'border-emerald-300 bg-emerald-50/30'
              : 'border-slate-300 hover:border-indigo-400 bg-slate-50/50'
          }`}
          role="button"
          tabIndex={0}
          aria-label="Upload PDF by drag and drop or clicking to browse"
        >
          <input
            ref={fileInputRef}
            type="file"
            accept=".pdf,application/pdf"
            onChange={handleFileChange}
            disabled={isUploading}
            className="hidden"
            data-testid="file-upload-input"
          />

          {file ? (
            <div className="flex flex-col items-center gap-2">
              <div className="w-12 h-12 rounded-xl bg-emerald-100 text-emerald-600 flex items-center justify-center shadow-xs">
                <CheckCircle className="w-6 h-6" />
              </div>
              <p className="font-semibold text-sm text-slate-800">{file.name}</p>
              <p className="text-xs text-slate-500">
                {(file.size / (1024 * 1024)).toFixed(2)} MB • Ready to upload
              </p>
            </div>
          ) : (
            <div className="flex flex-col items-center gap-2">
              <div className="w-12 h-12 rounded-xl bg-indigo-50 text-indigo-600 flex items-center justify-center shadow-xs">
                <UploadCloud className="w-6 h-6" />
              </div>
              <div>
                <p className="text-sm font-semibold text-slate-700">
                  Click to browse or drag and drop PDF
                </p>
                <p className="text-xs text-slate-500 mt-1">PDF format up to 25MB</p>
              </div>
            </div>
          )}
        </div>

        {/* Validation / Server Error Message */}
        {error && (
          <div className="flex items-center gap-2 p-3 rounded-lg bg-rose-50 border border-rose-200 text-rose-800 text-xs">
            <AlertCircle className="w-4 h-4 shrink-0 text-rose-600" />
            <span>{error}</span>
          </div>
        )}

        {/* Auto-process option */}
        <div className="flex items-center gap-2 pt-1">
          <input
            id="autoProcess"
            type="checkbox"
            checked={autoProcess}
            onChange={(e) => setAutoProcess(e.target.checked)}
            disabled={isUploading}
            className="rounded border-slate-300 text-indigo-600 focus:ring-indigo-500 w-4 h-4"
          />
          <label htmlFor="autoProcess" className="text-xs font-medium text-slate-700 cursor-pointer">
            Automatically extract text and generate vector embeddings upon upload
          </label>
        </div>

        {/* Upload Progress Bar */}
        {isUploading && (
          <div className="space-y-1.5 pt-2">
            <div className="flex justify-between text-xs font-medium text-slate-600">
              <span>Uploading document...</span>
              <span>{uploadProgress}%</span>
            </div>
            <div className="w-full bg-slate-200 rounded-full h-2 overflow-hidden">
              <div
                className="bg-indigo-600 h-2 rounded-full transition-all duration-200"
                style={{ width: `${uploadProgress}%` }}
              />
            </div>
          </div>
        )}

        {/* Action Buttons */}
        <div className="flex items-center justify-end gap-3 pt-4 border-t border-slate-100">
          <Button variant="ghost" onClick={handleClose} disabled={isUploading}>
            Cancel
          </Button>
          <Button
            onClick={handleUpload}
            disabled={!file || isUploading}
            isLoading={isUploading}
            leftIcon={<FileText className="w-4 h-4" />}
          >
            Upload Document
          </Button>
        </div>
      </div>
    </Modal>
  );
};
