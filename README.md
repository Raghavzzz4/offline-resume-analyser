Enterprise Offline Document Analysis Engine (Local RAG)
A privacy-first, fully offline Retrieval-Augmented Generation (RAG) pipeline designed to execute multi-document semantic queries over complex, unstructured PDF layouts. Built entirely using Java, LangChain4j, and Apache PDFBox, the system interfaces with local LLMs via Ollama without leaking data to cloud APIs.

🛠️ Architectural Highlights
Dynamic Layout-Aware Extraction Engine: Uses custom geometric scanning routines via PDFTextStripperByArea to dynamically detect multi-column vs. single-column text structures. Isolates horizontal header blocks (e.g., name, contact details) before executing full vertical column bisections, preventing the common text-zipper degradation effect.

Directory State Vector Persistence Layer: Implements high-performance workspace serialization. The system computes an automated cryptographic-like file system state footprint using file sizes and count. If the target directory remains unchanged, it bypasses the parsing extraction pipeline entirely and boots instantly from a local vector database index cache (multi_doc_vectors.json).

Asynchronous Token Streaming Architecture: Replaces blocking inference paradigms with a real-time OllamaStreamingChatModel coupled with a thread-locked CompletableFuture synchronizer to stream token arrays back to the terminal interface instantly.

Extended Compute Timeout Safeguard: Configured with a dedicated Duration.ofMinutes(5) network buffer on the underlying OkHttp connection, ensuring large-context multi-document comparative synthesis doesn't drop due to local hardware pre-fill latencies.

Strict Factuality Prompt Enforcement: Configured with a deterministic temperature boundary (0.0) and strict system prompt constraints to force zero conversational hallucination and absolute reliance on the textual context array.

🚀 Tech Stack
Core Logic: Java 17+

Orchestration: LangChain4j Core Framework

Layout Parsing: Apache PDFBox (Area & Spatial Text Strippers)

Local Embedding Architecture: AllMiniLmL6V2EmbeddingModel (Local Vectorization)

Inference Compute Layer: Ollama Server running Llama 3.2

⚙️ Quick Start Setup
Prerequisites
Ensure Java 17 or higher is installed and mapped to your system path environment variables.

Install and launch the local Ollama Engine.

Pull the processing model using your terminal:

Bash
ollama pull llama3.2
Execution Steps
Clone the project repository and navigate into the source root directory.

Run a clean Maven build loop once to auto-generate the file system workspace directories:

Bash
mvn clean compile exec:java
Drop your targeted multi-column resumes, profiles, or source documents into the freshly generated documents/ folder.

Execute the runtime engine:

Bash
mvn exec:java
Input your analytical queries into the console chat line interface, and type END on a new line to process inputs. Type exit to cleanly tear down runtime threads.

📊 Sample Query Performance Pattern
Plaintext
User Query: 
> compare their skills and list all projects fully
END

System Analysis Stream:
📁 SOURCE DOCUMENT: sample_2.pdf
=========================================
[Fact-driven comparative synthesis streamed token-by-token directly from local GPU boundaries]
