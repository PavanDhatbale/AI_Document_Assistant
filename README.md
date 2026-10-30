# AI Document Assistant (RAG)

An enterprise-grade, multi-tenant Retrieval-Augmented Generation (RAG) platform built with **Java 21**, **Spring Boot 3.4**, **Spring AI 1.1**, **MySQL 8**, **Qdrant**, and **Google Gemini**, paired with a **React 19 + TypeScript + Vite + Tailwind CSS** frontend.

---

## 1. High-Level Architecture

```
                       +---------------------------------------+
                       |   React 19 Frontend (Vite + Tailwind) |
                       |    - Document Manager & Polling       |
                       |    - Conversational QA Workspace      |
                       |    - Interactive Citation Cards       |
                       +-------------------+-------------------+
                                           | HTTP (stateless JWT)
                                           v
+-----------------------------------------------------------------------------------+
| Spring Boot 3.4.x Backend (Java 21)                                               |
|                                                                                   |
|  [Security Layer]                                                                 |
|   - Stateless JWT Filter (HMAC-SHA256, >= 256-bit key)                            |
|   - BCrypt Password Hashing (Strength 12)                                         |
|   - Strict User Ownership Isolation on all endpoints                              |
|                                                                                   |
|  [Document Engine]                                                                |
|   - Apache PDFBox 3.x structural validation & page-aware text extraction          |
|   - Sliding-window semantic chunker with token overlap                            |
|   - Multi-layer error recovery (automatic rollback on failure)                    |
|                                                                                   |
|  [RAG & AI Pipeline]                                                              |
|   - Embedding: Google Gemini gemini-embedding-001 (768-dim via MRL)               |
|   - Vector DB: Qdrant vector similarity search with user-scoped payload filter    |
|   - Chat Synthesis: Google Gemini (gemini-3.5-flash-lite / gemini-3.8-flash)      |
|   - Server-side Citation Validator (exact page mapping, invented tag rejection)   |
|   - Insufficient-evidence guardrail (suppresses citations on unanswerable queries)|
+-------------------+-------------------------------+-------------------------------+
                    |                               |
                    v                               v
        +-----------------------+       +-----------------------+
        |   MySQL 8 Database    |       |  Qdrant Vector DB     |
        |  - Users & Auth       |       |  - 768-dim Vectors    |
        |  - Document Metadata  |       |  - Cosine Distance    |
        |  - Relational Chunks  |       |  - Multi-tenant filter|
        |  - Conversations      |       +-----------------------+
        |  - Message Sources    |
        +-----------------------+
```

---

## 2. Prerequisites

- **Java Development Kit (JDK)**: Java 21 LTS
- **Build Tool**: Apache Maven 3.9+
- **Node.js**: Node.js 18+ (Node 20+ recommended) and npm
- **Relational Database**: MySQL 8.0+ running on `localhost:3306`
- **Vector Database**: Qdrant running on `localhost:6333` (REST) and `localhost:6334` (gRPC)
- **AI Credentials**: Google Gemini API key from [Google AI Studio](https://aistudio.google.com/)

---

## 3. Configuration & Environment Variables

Copy `.env.example` to `.env` in the root directory:

```bash
cp .env.example .env
```

Configure the following essential variables in `.env`:

| Variable | Description | Example / Default |
|---|---|---|
| `SERVER_PORT` | Spring Boot server port | `8080` |
| `DB_HOST` | MySQL hostname | `localhost` |
| `DB_PORT` | MySQL port | `3306` |
| `DB_NAME` | MySQL database name | `ai_document_assistant` |
| `DB_USER` | MySQL username | `root` |
| `DB_PASSWORD` | MySQL password | *(Your database password)* |
| `JWT_SECRET` | Base64 or UTF-8 HMAC-SHA256 secret (>= 256 bits) | *(Unique 32+ byte string)* |
| `GEMINI_API_KEY` | Google Gemini Developer API key | *(From Google AI Studio)* |
| `GEMINI_MODEL` | Gemini Chat model | `gemini-3.5-flash-lite` |
| `GEMINI_EMBEDDING_MODEL` | Gemini Embedding model | `gemini-embedding-001` |
| `GEMINI_EMBEDDING_DIMENSION`| Embedding output vector dimension | `768` |
| `QDRANT_HOST` | Qdrant gRPC host | `localhost` |
| `QDRANT_PORT` | Qdrant gRPC port | `6334` |
| `QDRANT_COLLECTION_NAME` | Vector collection name | `document_embeddings` |
| `QDRANT_VECTOR_SIZE` | Vector dimension (must match embedding dimension) | `768` |

> [!WARNING]
> Never commit `.env` or real API keys to version control. `.env` is ignored by `.gitignore`.

---

## 4. Quick Start & Execution

### Step 1: Start Supporting Services
Ensure MySQL and Qdrant are running locally:
- **MySQL**: Accessible on port `3306` with database `ai_document_assistant` created.
- **Qdrant**: Accessible on REST port `6333` and gRPC port `6334`.

### Step 2: Start the Backend (Spring Boot)
From the project root:
```bash
mvn clean spring-boot:run
```
The backend initializes database migrations via Flyway, ensures Qdrant collection `document_embeddings` exists with 768 dimensions and payload indexes, and listens on `http://localhost:8080`.

### Step 3: Start the Frontend (React + Vite)
In a separate terminal:
```bash
cd frontend
npm install
npm run dev
```
The frontend starts on `http://localhost:5173`.

---

## 5. Automated Test Suite

Both backend and frontend contain automated test suites:

### Backend Tests (123 tests)
```bash
mvn test
```
- **Authentication**: JWT generation, token parsing, BCrypt validation, role least-privilege.
- **Controllers**: Document upload, retrieval, deletion, conversation creation, messaging, error formatting.
- **Ingestion & Vectors**: Text extraction, semantic chunking, embedding generation, Qdrant indexing, and rollbacks.
- **RAG Pipeline**: Scoped retrieval, citation extraction, invalid source rejection, insufficient-evidence suppression.

### Frontend Tests, Lint, and Production Build (21 tests)
```bash
cd frontend
npm test -- --run
npm run lint
npm run build
```
- **Vitest & React Testing Library**: Tests composer disabled states, citation card expansion, assistant messages, insufficient-evidence rendering, and navigation.
- **ESLint & TypeScript**: Zero warnings, strict typing.

---

## 6. End-to-End Demo Checklist

Follow this workflow to demonstrate the application:

1. **User Registration & Login:**
   - Navigate to `http://localhost:5173/register`.
   - Register a new user account. Notice that users are assigned least-privilege `ROLE_USER`.
   - Log in to receive a signed JWT token and view the Dashboard.
2. **Document Upload & Ingestion:**
   - Navigate to `/documents`.
   - Upload a multi-page PDF document (e.g. `SQL Notes by Apna College (1).pdf`).
   - Observe status progression: `PENDING` $\rightarrow$ `PROCESSING` $\rightarrow$ `READY`.
   - Click the document to view extracted chunks and verified page numbers.
3. **Conversational RAG with Citations:**
   - Navigate to `/conversations` and create a **New Conversation**.
   - Check the uploaded document in the **Document Scope** panel.
   - Ask an answerable question (e.g., *"What is TRUNCATE TABLE in this document and what does it do to data?"*).
   - Observe the response:
     - Grounded explanation with inline tag `[Source 1]`.
     - Interactive citation card below the message showing document name, **Page 4**, `#chunk-3`, match percentage, and expandable excerpt.
     - Note that no redundant markdown citation footer is appended to the text bubble.
4. **Hallucination Prevention & Insufficient Evidence:**
   - In the same conversation, ask an out-of-domain question (e.g., *"What is photosynthesis and how does chlorophyll work?"*).
   - Observe the response:
     - Exact guardrail fallback: *"I could not find sufficient information in the available documents to answer your question. Please ensure relevant documents are indexed or try rephrasing."*
     - Exactly **0 citation cards** rendered.
5. **Multi-Tenant Isolation & Cascading Cleanup:**
   - Deleting a document removes its file from disk, cascades deletions of relational chunks from MySQL, and purges all corresponding vector points from Qdrant.

---

## 7. Known Disclosures & Operational Notes

- **Qdrant Network Exposure (Local Dev)**: In local standalone setups, Qdrant binds to `0.0.0.0` by default. In production deployments, Qdrant should be bound to `127.0.0.1` or placed behind a secure reverse proxy with TLS and an API key (`QDRANT_API_KEY`).
- **Gemini Free-Tier Model Demand**: Gemini models can experience intermittent high demand (HTTP 503). The backend handles this gracefully via `parseGenAiError` diagnostics and recommends `gemini-3.5-flash-lite` for reliable throughput.
- **JWT Dev Fallback Safeguard**: The development fallback key is strictly blocked in production profiles (`prod`, `production`), causing immediate startup termination if `JWT_SECRET` is missing.
