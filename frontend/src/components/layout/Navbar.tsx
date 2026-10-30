import React from 'react';
import { Link } from 'react-router-dom';
import { Menu, Sparkles, User } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';

interface NavbarProps {
  onToggleSidebar: () => void;
}

export const Navbar: React.FC<NavbarProps> = ({ onToggleSidebar }) => {
  const { user } = useAuth();

  return (
    <header className="h-16 lg:hidden flex items-center justify-between px-4 bg-white border-b border-slate-200 shrink-0">
      <div className="flex items-center gap-2">
        <button
          onClick={onToggleSidebar}
          className="p-2 -ml-2 rounded-lg text-slate-600 hover:text-slate-900 hover:bg-slate-100 focus:outline-none focus:ring-2 focus:ring-indigo-500"
          aria-label="Toggle navigation menu"
        >
          <Menu className="w-5 h-5" />
        </button>
        <div className="flex items-center gap-2">
          <div className="w-7 h-7 rounded-md bg-indigo-50 border border-indigo-200 flex items-center justify-center text-indigo-600">
            <Sparkles className="w-3.5 h-3.5" />
          </div>
          <span className="font-bold text-slate-900 text-sm tracking-tight">DocAssistant AI</span>
        </div>
      </div>

      <Link
        to="/profile"
        className="w-8 h-8 rounded-full bg-indigo-100 border border-indigo-200 flex items-center justify-center text-indigo-700 font-bold text-xs uppercase hover:ring-2 hover:ring-indigo-400 transition"
        title="View Profile"
        aria-label="View Profile"
      >
        {user?.displayName ? user.displayName.charAt(0) : <User className="w-4 h-4" />}
      </Link>
    </header>
  );
};
