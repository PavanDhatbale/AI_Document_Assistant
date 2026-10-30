import React from 'react';

interface LoadingSpinnerProps {
  size?: 'sm' | 'md' | 'lg';
  className?: string;
  label?: string;
}

export const LoadingSpinner: React.FC<LoadingSpinnerProps> = ({
  size = 'md',
  className = '',
  label = 'Loading...',
}) => {
  const sizeClasses = {
    sm: 'w-4 h-4 border-2',
    md: 'w-6 h-6 border-2',
    lg: 'w-10 h-10 border-3',
  };

  return (
    <div className={`flex items-center justify-center gap-2.5 ${className}`} role="status">
      <div
        className={`${sizeClasses[size]} border-slate-300 border-t-indigo-600 rounded-full animate-spin`}
        aria-hidden="true"
      />
      {label && <span className="sr-only">{label}</span>}
    </div>
  );
};
