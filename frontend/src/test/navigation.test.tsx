import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AppLayout } from '../components/layout/AppLayout';
import { AuthProvider } from '../context/AuthContext';
import { ToastProvider } from '../context/ToastContext';

// Mock authApi to provide an authenticated user
vi.mock('../api/auth', () => ({
  authApi: {
    getProfile: vi.fn().mockResolvedValue({
      id: 'user-nav-1',
      email: 'nav@example.com',
      displayName: 'Nav User',
      role: 'ROLE_USER',
      createdAt: '2026-10-24T12:00:00Z',
    }),
    logout: vi.fn(),
  },
}));

describe('Responsive Navigation Behavior', () => {
  // 12. Responsive navigation behavior where practical
  it('toggles mobile sidebar drawer upon clicking navigation menu', async () => {
    render(
      <MemoryRouter>
        <ToastProvider>
          <AuthProvider>
            <AppLayout />
          </AuthProvider>
        </ToastProvider>
      </MemoryRouter>
    );

    // Sidebar navigation is rendered with aria-label
    const sidebar = screen.getByLabelText('Main Navigation');
    expect(sidebar).toBeInTheDocument();

    // The mobile menu button in Navbar has aria-label="Toggle navigation menu"
    const toggleButton = screen.getByLabelText('Toggle navigation menu');
    expect(toggleButton).toBeInTheDocument();

    // Before clicking, sidebar has translate-x-full on mobile
    expect(sidebar.className).toContain('-translate-x-full');

    // Click toggle button to open mobile drawer
    fireEvent.click(toggleButton);

    // Sidebar should now have translate-x-0
    expect(sidebar.className).toContain('translate-x-0');

    // Close button inside sidebar
    const closeButton = screen.getByLabelText('Close navigation');
    fireEvent.click(closeButton);

    // Sidebar closes
    expect(sidebar.className).toContain('-translate-x-full');
  });
});
