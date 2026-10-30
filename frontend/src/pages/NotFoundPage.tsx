import React from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '../components/common/Button';
import { AlertCircle, Home } from 'lucide-react';

export const NotFoundPage: React.FC = () => {
  const navigate = useNavigate();

  return (
    <div className="h-screen w-screen flex flex-col items-center justify-center p-4 bg-slate-50 text-center">
      <div className="w-14 h-14 rounded-2xl bg-rose-50 border border-rose-100 flex items-center justify-center text-rose-600 mb-4 shadow-xs">
        <AlertCircle className="w-7 h-7" />
      </div>
      <h1 className="text-3xl font-bold text-slate-900 tracking-tight">404 - Page Not Found</h1>
      <p className="text-sm text-slate-500 max-w-sm mt-2 mb-6">
        The page you requested does not exist or has been moved.
      </p>
      <Button onClick={() => navigate('/dashboard')} leftIcon={<Home className="w-4 h-4" />}>
        Back to Dashboard
      </Button>
    </div>
  );
};
