# AI Document Assistant - Frontend Application

A responsive React + Vite + Tailwind CSS frontend interface for the AI Document Assistant RAG platform.

---

## 1. Stack and Technologies

- **Core**: React 18 with TypeScript 5
- **Build Tool**: Vite 6
- **Styling**: Tailwind CSS 3.4 with custom SaaS color palette
- **Routing**: React Router DOM 6
- **HTTP Client**: Axios with centralized request/response interceptors
- **Icons**: Lucide React
- **Testing**: Vitest with React Testing Library and JSDOM

---

## 2. Directory Structure

```
frontend/
├── .env.example              # Public Vite environment template
├── eslint.config.js          # ESLint 9 configuration
├── index.html                # Entry HTML template
├── package.json              # Dependencies and scripts
├── tailwind.config.js        # Tailwind CSS theme configuration
├── tsconfig.json             # TypeScript compiler configuration
├── vite.config.ts            # Vite application bundler configuration
├── vitest.config.ts          # Vitest testing configuration
└── src/
    ├── api/                  # Axios HTTP client and API services
    │   ├── client.ts         # Centralized Axios instance, TokenManager, and interceptors
    │   ├── auth.ts           # Authentication and profile endpoints
    │   ├── documents.ts      # Document upload, chunking, and management endpoints
    │   └── conversations.ts  # Conversations, scoping, and grounded RAG chat endpoints
    ├── components/
    │   ├── common/           # Reusable UI primitives (Button, Input, Modal, Badge, Toast)
    │   ├── layout/           # AppLayout, Sidebar, Navbar, ProtectedRoute
    │   ├── documents/        # DocumentUploadModal, DocumentCard, DocumentList, StatusBadge
    │   └── chat/             # MessageComposer, MessageItem, MessageList, CitationCard, DocumentScopeSelector
    ├── context/
    │   ├── AuthContext.tsx   # Authenticated session and user state
    │   └── ToastContext.tsx  # User feedback notifications/toasts
    ├── pages/
    │   ├── LoginPage.tsx     # Sign-in form with client validation
    │   ├── RegisterPage.tsx  # Registration form with client validation
    │   ├── DashboardPage.tsx # Saas overview, repository stats, quick actions
    │   ├── DocumentsPage.tsx # PDF management, upload progress, polling, deletion
    │   ├── ConversationsPage.tsx # Grounded chat interface, document scoping, citations
    │   ├── ProfilePage.tsx   # User profile details and session policies
    │   └── NotFoundPage.tsx  # 404 fallback page
    ├── test/                 # Automated test suite (20 tests)
    └── types/                # TypeScript interfaces aligned with Spring Boot DTOs
```

---

## 3. Environment Configuration

Copy `.env.example` to create `.env` in the `frontend/` directory:

```bash
cp .env.example .env
```

| Variable | Default Value | Description |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8080/api` | Base URL pointing to the Spring Boot REST API |
| `VITE_MAX_FILE_SIZE_BYTES` | `26214400` (25MB) | Client-side file size constraint before upload |
| `VITE_AUTH_STORAGE_MODE` | `session` | Storage strategy: `session` (tab-scoped) or `memory` (pure in-memory) |

> **Security Note**: All `VITE_*` environment variables are bundled into the public client application. Never put backend secrets, database passwords, Gemini API keys, Qdrant keys, or JWT signing secrets here.

---

## 4. Development & Running

### Install Dependencies
```bash
npm install
```

### Start Development Server
```bash
npm run dev
```
The application will launch at `http://localhost:5173`.

### Run Automated Tests
```bash
npm test
```

### Run Linter
```bash
npm run lint
```

### Build for Production
```bash
npm run build
```
Production assets are generated in `frontend/dist/`.

---

## 5. Security & Authentication Architecture

- **Token Storage Strategy**:
  - `sessionStorage` (default): Persists token across page refreshes within the same browser tab, but automatically clears upon closing the tab. Never shared across multiple tabs.
  - `memory` (configurable via `VITE_AUTH_STORAGE_MODE=memory`): Completely in-memory; immune to web storage theft, but resets upon page refresh.
- **Centralized Interceptor**: Automatically injects `Authorization: Bearer <jwt_token>` into outbound requests.
- **Session Expiration (401)**: Response interceptor intercepts expired or invalid tokens, purges the client session, and cleanly redirects to the login screen without infinite redirect loops.
- **Protected Routes**: React Router route wrapper enforces authentication, redirecting unauthenticated visitors to `/login` and redirecting back to their target page upon sign-in.
