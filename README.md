
# Enhancing LLM Code Understanding for Large Codebases

## Overview

This project implements a system to improve Large Language Model (LLM) understanding of large Java codebases. It operates entirely locally, leveraging local LLMs for embedding and chat, and a local vector database for storing code representations. The system parses Java code into semantic chunks, generates vector embeddings, and allows users to perform semantic searches and receive LLM-generated explanations and insights about the codebase. Dynamic updates ensure the index remains current with code changes.

## Core Features

*   **Local-First Processing:** All operations, including LLM inference and data storage, occur on the user's machine, ensuring data privacy.
*   **AST-Aware Code Parsing:** Java code is parsed using Abstract Syntax Trees (AST) via JavaParser to create semantically meaningful code segments (classes, methods, etc.).
*   **Local Vector Embeddings:** Code segments are converted into vector embeddings using a locally deployed LLM (e.g., `nomic-embed-text` via Ollama).
*   **Semantic Code Search:** Enables natural language queries against the indexed codebase, retrieving relevant code segments based on semantic similarity.
*   **LLM-Powered Code Comprehension:** Integrates a local chat LLM (e.g., `mistral-openorca`, `qwen2.5-coder:7b` via Ollama) to provide explanations, answer questions, and offer insights based on retrieved code context.
*   **Dynamic Index Updates:** Monitors the codebase for file changes and incrementally updates the embeddings and vector store.
*   **Web-Based User Interface:** An Angular frontend provides an interface for configuring the codebase path, initiating indexing, managing chat history, and interacting with the LLM.
*   **Optional Re-ranking:** Experimental feature to use an LLM (e.g., `codellama:7b-instruct`) to re-rank retrieved code segments for relevance.

## Technology Stack

*   **Backend:**
    *   Java 21
    *   Spring Boot 3.x
    *   JavaParser (for AST-based code parsing)
    *   Apache HttpClient 5 (Async for Ollama/ChromaDB communication)
    *   H2 Database Engine (for conversation history)
    *   Maven (Build and dependency management)
*   **Frontend:**
    *   Angular (latest stable, e.g., 17)
    *   TypeScript
    *   Angular Material (UI components)
    *   `ngx-markdown` (Markdown rendering)
    *   `ngx-highlightjs` (Syntax highlighting)
    *   RxJS (Asynchronous operations)
*   **AI / Data:**
    *   **Ollama:** For local deployment and management of LLMs (embedding, chat, re-ranking).
        *   Embedding Model: e.g., `nomic-embed-text`
        *   Chat Model: e.g., `mistral-openorca:latest`, `qwen2.5-coder:7b`
        *   Re-ranking Model (optional): e.g., `codellama:7b-instruct`
    *   **ChromaDB:** Local vector database for storing and searching code embeddings (run via Docker).

## System Architecture

The system follows a client-server architecture:
*   **Frontend (Client):** Angular single-page application for user interaction.
*   **Backend (Server):** Spring Boot application providing REST APIs for indexing, querying, history management, and LLM/vector store orchestration.
All components, including Ollama and ChromaDB, are designed to run locally on the developer's machine.

## Prerequisites

*   Java JDK 21 or later
*   Maven 3.6+
*   Node.js (latest LTS version recommended) and npm
*   Angular CLI (`npm install -g @angular/cli`)
*   Docker Desktop (or Docker Engine)
*   Ollama (installed and running: [https://ollama.com/](https://ollama.com/))

## Setup and Execution

### 1. Ollama Setup

Ensure Ollama is installed and running. Pull the required models:
```bash
ollama pull nomic-embed-text
ollama pull mistral-openorca # or qwen2.5-coder:7b or another preferred chat model
ollama pull codellama:7b-instruct # if using the re-ranker feature
```
Verify models are available: `ollama list`

### 2. ChromaDB Setup

Run ChromaDB using Docker:
```bash
docker pull chromadb/chroma
docker run -d -p 8000:8000 chromadb/chroma
```
This will start ChromaDB and expose its API on port 8000.

### 3. Backend Setup

Navigate to the backend project root (where `pom.xml` is located).

*   **Configuration:**
    *   Open `src/main/resources/application.properties`.
    *   Verify/update `ollama.*` properties if your Ollama setup differs from default (e.g., different models).
    *   Verify/update `chromadb.url` if ChromaDB is running on a different port.
    *   **Crucially, set `chromadb.embedding-dimension` to match the output dimension of your chosen Ollama embedding model** (e.g., `nomic-embed-text` typically outputs 768 dimensions).
    *   Set `codebase.path` to a default Java project path for initial testing if desired (this can be changed in the UI).
*   **Build and Run:**
    ```bash
    mvn clean install
    mvn spring-boot:run
    ```
    The backend will start, typically on port 8080.

### 4. Frontend Setup

Navigate to the frontend project root (e.g., `angular-ui`, where `angular.json` is located).

*   **Install Dependencies:**
    ```bash
    npm install
    ```
*   **Run Development Server:**
    ```bash
    ng serve
    ```
    The frontend will typically be available at `http://localhost:4200/`. It proxies API requests to the backend (usually `http://localhost:8080/api`).

## Configuration

*   **Backend:** Primary configuration is in `src/main/resources/application.properties`. Key settings include:
    *   `server.port`
    *   `spring.datasource.url` (for H2 history database)
    *   `ollama.baseUrl`, `ollama.embeddingModel`, `ollama.chatModel`
    *   `chromadb.url`, `chromadb.defaultCollectionName`, `chromadb.embedding-dimension`
    *   `parser.maxSegmentCharLength`
    *   `reranker.enabled`, `reranker.model`
    *   `codebase.path` (initial default, can be overridden by UI)
*   **Frontend:**
    *   API proxy configuration is in `angular.json` (or a `proxy.conf.json` file if used).
    *   Chat settings (model, temperature, re-ranker options) are stored in browser `localStorage`.

## Usage

1.  **Access the UI:** Open `http://localhost:4200/` in your browser.
2.  **Initial Setup (if prompted):** If it's the first run or no model is configured, you might be guided to select a default chat LLM model from those available via Ollama.
3.  **Codebase Indexing:**
    *   Navigate to the "Codebase Settings" page (e.g., via a settings icon).
    *   Enter the absolute path to the root directory of the Java codebase you want to index.
    *   Click "Start/Update Index".
    *   The system will parse files, generate embeddings, and store them in ChromaDB. Progress and status will be displayed.
4.  **Querying the Codebase:**
    *   Navigate to the main chat interface.
    *   Type your natural language questions about the indexed codebase (e.g., "Explain the purpose of the `CacheManager` class", "How does `UserService` handle authentication?", "Show me examples of using the `Stream API` in this project").
    *   The system will retrieve relevant code snippets and use the LLM to generate an answer.
    *   Retrieved source code snippets will be displayed alongside the LLM's response.
    *   Chat history is saved and can be accessed via the conversation list panel.
    *   Chat settings (LLM model, temperature, re-ranker options) can be adjusted from the chat interface.

## Project Structure (High-Level)

*   **Backend (`/` or `backend/` - Java/Spring Boot):**
    *   `src/main/java/com/localllm/assistant/`:
        *   `config/`: Spring Boot configuration classes for beans, async, Ollama, ChromaDB.
        *   `controller/`: REST API endpoints and DTOs.
        *   `embedding/`: Service for generating code embeddings via Ollama.
        *   `exception/`: Custom application exceptions.
        *   `history/`: JPA entities and repositories for conversation history.
        *   `llm/`: Client for interacting with Ollama chat API.
        *   `parser/`: Service and models for AST-based Java code parsing.
        *   `service/`: Core business logic (indexing, querying, file monitoring, updates, re-ranking).
        *   `util/`: Utility classes.
        *   `vectorstore/`: Client and service for interacting with ChromaDB.
    *   `src/main/resources/`: `application.properties`, `log4j2.xml`.
*   **Frontend (`angular-ui/` or `frontend/` - Angular):**
    *   `src/app/`:
        *   `components/`: Angular components for different UI parts (chat, settings, conversation list).
        *   `guards/`: Route guards (e.g., for initial setup).
        *   `models/`: TypeScript interfaces for data structures.
        *   `services/`: Angular services for API communication.
    *   `src/assets/`: Static assets.
    *   `src/environments/`: Environment-specific configurations.


