import React, { useEffect, useRef, useState, useCallback } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';

interface GoogleAuthButtonProps {
  mode?: 'signin' | 'signup';
  disabled?: boolean;
}

declare global {
  interface Window {
    google?: {
      accounts: {
        id: {
          initialize: (config: {
            client_id: string;
            callback: (response: { credential?: string; select_by?: string }) => void;
            auto_select?: boolean;
            cancel_on_tap_outside?: boolean;
          }) => void;
          renderButton: (
            parent: HTMLElement,
            options: {
              type?: 'standard' | 'icon';
              theme?: 'outline' | 'filled_blue' | 'filled_black';
              size?: 'large' | 'medium' | 'small';
              text?: 'signin_with' | 'signup_with' | 'continue_with';
              shape?: 'rectangular' | 'pill' | 'circle' | 'square';
              logo_alignment?: 'left' | 'center';
              width?: number | string;
            }
          ) => void;
          prompt: (momentListener?: (notification: unknown) => void) => void;
        };
      };
    };
  }
}

export const GoogleAuthButton: React.FC<GoogleAuthButtonProps> = ({
  mode = 'signin',
  disabled = false,
}) => {
  const [isLoading, setIsLoading] = useState(false);
  const [gisRendered, setGisRendered] = useState(false);
  const gisContainerRef = useRef<HTMLDivElement>(null);

  const { loginWithGoogle } = useAuth();
  const { addToast } = useToast();
  const navigate = useNavigate();
  const location = useLocation();

  const from = (location.state as { from?: { pathname: string } })?.from?.pathname || '/dashboard';
  const googleClientId = import.meta.env.VITE_GOOGLE_CLIENT_ID || '';

  const handleCredentialResponse = useCallback(
    async (response: { credential?: string }) => {
      if (!response.credential) {
        addToast('No credentials received from Google.', 'error');
        return;
      }

      setIsLoading(true);
      try {
        await loginWithGoogle(response.credential);
        addToast(
          mode === 'signup'
            ? 'Account created and signed in with Google!'
            : 'Welcome back! Signed in with Google successfully.',
          'success'
        );
        navigate(from, { replace: true });
      } catch (err: unknown) {
        const apiErr = err as { response?: { status?: number; data?: { message?: string } }; message?: string };
        const status = apiErr.response?.status;
        const errorMsg =
          apiErr.response?.data?.message ||
          apiErr.message ||
          'Google authentication failed. Please try again.';

        if (status === 409) {
          addToast(
            'An account with this email already exists with password authentication. Please sign in with your email and password.',
            'error'
          );
        } else {
          addToast(errorMsg, 'error');
        }
      } finally {
        setIsLoading(false);
      }
    },
    [loginWithGoogle, mode, from, navigate, addToast]
  );

  // Initialize and render Google Identity Services button if available
  useEffect(() => {
    if (!googleClientId || !window.google?.accounts?.id || !gisContainerRef.current) {
      return;
    }

    try {
      window.google.accounts.id.initialize({
        client_id: googleClientId,
        callback: handleCredentialResponse,
        auto_select: false,
        cancel_on_tap_outside: true,
      });

      // Clear previous buttons if re-rendering
      gisContainerRef.current.innerHTML = '';

      window.google.accounts.id.renderButton(gisContainerRef.current, {
        type: 'standard',
        theme: 'outline',
        size: 'large',
        text: mode === 'signup' ? 'signup_with' : 'continue_with',
        shape: 'rectangular',
        logo_alignment: 'left',
        width: 384, // matches max-w-md form width
      });

      setGisRendered(true);
    } catch {
      setGisRendered(false);
    }
  }, [googleClientId, mode, handleCredentialResponse]);

  const handleCustomButtonClick = () => {
    if (!googleClientId) {
      addToast(
        'Google Client ID is not configured. Please set VITE_GOOGLE_CLIENT_ID in your environment.',
        'warning'
      );
      return;
    }

    if (window.google?.accounts?.id) {
      window.google.accounts.id.initialize({
        client_id: googleClientId,
        callback: handleCredentialResponse,
        auto_select: false,
      });
      window.google.accounts.id.prompt();
    } else {
      addToast('Google Identity Services is loading. Please try again in a moment.', 'info');
    }
  };

  const buttonText =
    mode === 'signup' ? 'Sign up with Google' : 'Continue with Google';

  return (
    <div className="w-full">
      {/* Official GIS Button Container (rendered when Google SDK is loaded) */}
      <div
        ref={gisContainerRef}
        className={`w-full flex justify-center [&>div]:w-full ${
          gisRendered ? 'block' : 'hidden'
        }`}
        data-testid="google-gis-container"
      />

      {/* High-quality accessible button (visible when GIS is loading, not yet configured, or in tests) */}
      {!gisRendered && (
        <button
          type="button"
          onClick={handleCustomButtonClick}
          disabled={disabled || isLoading}
          data-testid="google-auth-button"
          aria-label={buttonText}
          className="w-full flex items-center justify-center gap-3 px-4 py-2.5 bg-white border border-slate-300 rounded-xl text-sm font-medium text-slate-700 hover:bg-slate-50 hover:border-slate-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 shadow-2xs transition-all duration-150 disabled:opacity-60 disabled:cursor-not-allowed"
        >
          {isLoading ? (
            <div className="w-5 h-5 border-2 border-indigo-600 border-t-transparent rounded-full animate-spin" />
          ) : (
            <svg
              className="w-5 h-5 shrink-0"
              viewBox="0 0 24 24"
              aria-hidden="true"
            >
              <path
                fill="#4285F4"
                d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"
              />
              <path
                fill="#34A853"
                d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"
              />
              <path
                fill="#FBBC05"
                d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.06H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.94l2.85-2.22.81-.63z"
              />
              <path
                fill="#EA4335"
                d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.06l3.66 2.84c.87-2.6 3.3-4.52 6.16-4.52z"
              />
            </svg>
          )}
          <span>{isLoading ? 'Connecting to Google...' : buttonText}</span>
        </button>
      )}
    </div>
  );
};
