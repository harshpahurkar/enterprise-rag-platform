# Enterprise RAG Platform architecture
**Version 1.0.0** · Harsh Pahurkar · 2026-09-27 · HADS 1.0.0

Design notes behind the [README](../README.md). The approved plan is [design.md](design.md); where they differ, this file describes the code as built. Latency numbers live in [benchmark.md](benchmark.md).

---

## AI READING INSTRUCTION

Read `[SPEC]` and `[BUG]` blocks for authoritative facts.
Read `[NOTE]` only if additional context is needed.
`[?]` blocks are unverified; treat them with lower confidence.

---

## 1. Components

**[SPEC]**
| Class (package `com.harshpahurkar.rag`) | Responsibility |
|---|---|
| `security.SecurityConfig` | Filter chain, JWT encoder and decoder, BCrypt, CSP header, ADMIN-only write routes |
| `security.AuthController` | `POST /api/auth/login`, issues the JWT |
| `security.Roles` | The six role names; strips `ROLE_` and drops non-role authorities |
| `document.DocumentController`, `document.IngestionService` | Upload, list readable documents, delete |
| `search.Retriever` | Query embedding plus the role-filtered HNSW SQL, timed as `retrievalMs` |
| `search.SearchController` | `POST /api/search`, no LLM |
| `ai.AskController`, `ai.AskService` | `POST /api/ask`: injection check, retrieval, score gate, source numbering, Assistant call, error mapping |
| `ai.Assistant` | LangChain4j AI Service: `prompts/system.txt`, `prompts/answer.txt`, input and output guardrails |
| `ai.AiConfig` | Embedding model (fails startup unless dimension is 384), `ChatModel`, `Assistant` beans |
| `demo.DemoDataLoader` | `demo` profile only: upserts users, ingests `demo-docs/` when `document` is empty |

## 2. Authentication

**[SPEC]**
- Login: `POST /api/auth/login`, public, 10 requests per minute per IP.
- Token: HS256 JWT, issuer `rag-platform`, `exp` required, lifetime 1 h (`app.jwt.ttl`).
- Key: `JWT_SECRET`, at least 32 bytes, or startup fails.
- Roles: read from `app_user.roles` on every request. A role change or deleted user applies on the next request.
- Transport: `Authorization: Bearer`. The SPA stores the token in `sessionStorage`. No cookies, CSRF disabled, stateless sessions.
- Writes: `POST /api/documents` and `DELETE /api/documents/{id}` require ADMIN; other `/api/**` routes require any valid token.

## 3. Ingestion

**[SPEC]**
| Step | Detail |
|---|---|
| Limits | 25 MB per file, 2,000 chunks per document, 5,000,000 extracted characters |
| Parse | Apache Tika (`ApacheTikaDocumentParser`): PDF, DOCX, PPTX, HTML, MD, TXT and more |
| Split | `DocumentSplitters.recursive(1000, 150)`: paragraph, then line, sentence, word |
| Embed | BGE-small-en-v1.5, quantized ONNX, 384 dimensions, inside the JVM |
| Store | One transaction: the `document` row, then a batch insert of its `chunk` rows |
| Validate | `allowedRoles` non-empty, each one of ADMIN, HR, FINANCE, LEGAL, ENGINEERING, EMPLOYEE; a file with no text is a 400 |

**[NOTE]**
Parsing, splitting and embedding run before the transaction opens, so no pooled connection is held during the slow part of an upload.

## 4. Retrieval

**[SPEC]**
```sql
SELECT c.id, c.document_id, d.title, c.chunk_index, c.content,
       1 - (c.embedding <=> :q::vector) AS score
FROM chunk c JOIN document d ON d.id = c.document_id
WHERE d.allowed_roles && :roles::text[]
ORDER BY c.embedding <=> :q::vector
LIMIT :k
```
- Index: `chunk_embedding_hnsw`, `hnsw (embedding vector_cosine_ops)`.
- Per connection (Hikari `connection-init-sql`): `SET hnsw.iterative_scan = strict_order; SET plan_cache_mode = force_custom_plan`.
- One autocommit round trip per search; no `SET LOCAL` transaction.
- k: 6 for Ask (`app.rag.top-k`); Search defaults to 10 (`app.rag.search-k`) and accepts 1 to 50.
- Score gate: `app.rag.min-score` = 0.6.
- Calibration: lowest on-topic top-1 score 0.652, highest off-topic top-1 score 0.544. Question sets: `backend/src/test/resources/eval/`.
- Quality: 18 of 18 eval questions retrieve the expected document at rank 1 (`RetrievalQualityIT`).

## 5. Ask pipeline

**[SPEC]**
Preconditions: valid token, at most 20 asks per hour per user, question of 1 to 1,000 characters.
1. Injection check on the raw question. Match: 422, no retrieval.
2. Retrieve the top 6 chunks with the caller's roles.
3. Drop chunks scoring below 0.6. None left: return the refusal sentence with `refused: true` and `generationMs: 0`, no LLM call.
4. Number the rest as `[n] Title (part k)` inside `<sources>`, escaped so document text can't close the block.
5. `Assistant.answer(memoryId, question, context)`: input guardrails (injection, then PII masking), Claude, output guardrail (citations).
6. Return `{answer, refused, sources[{n, chunkId, documentId, title, chunkIndex, content, score}], retrievalMs, generationMs}`.

Refusal sentence: `I couldn't find that in the documents you have access to.`

**[SPEC]**
| Claude setting | Value |
|---|---|
| Model | `claude-opus-5-5`, overridable with `LLM_MODEL` |
| Client | `AnthropicChatModel` from `langchain4j-anthropic` |
| Sampling parameters | none sent |
| Effort | `output_config.effort = medium`, via `customParameters` |
| Refusal fallback | beta `server-side-fallback-2026-07-01` with `fallbacks: "default"` |
| Max tokens, timeout | 16000, 120 s |
| No API key | a placeholder `ChatModel` throws; Ask returns 503, everything else works |

## 6. Guardrails

**[SPEC]**
| Guardrail | Input | Rule | Result |
|---|---|---|---|
| `PromptInjectionGuardrail` | raw `question` variable only | ignore/disregard/forget/override + previous/prior instructions; reveal/print/show + system/hidden prompt; "you are now"; "developer mode"; "jailbreak"; `<sources>` tags | `fatal`, 422 |
| `PiiMaskingGuardrail` | whole rendered user message | emails, Luhn-valid 13 to 19 digit card numbers, SIN (`###-###-###`, `### ### ###`), SSN (`###-##-####`), North American phones | `successWith(masked)`; placeholders `[EMAIL]`, `[CARD]`, `[GOV_ID]`, `[PHONE]` |
| `CitationGuardrail` | Claude's answer | cites sources as `[n]` or `[1, 2]`, numbered between 1 and the source count, or equals the refusal sentence | `reprompt` once, then 422 |

- `@OutputGuardrails(maxRetries = 2)`: LangChain4j counts total attempts, so 2 means one answer plus at most one re-prompt.
- The system prompt marks `<sources>` content as untrusted and tells Claude to keep the PII placeholders as they are.
- Masking covers only the request to Anthropic. The API response returns the caller's own sources unmasked.

**[NOTE]**
The injection check reads only the question, never the retrieved context, so a document that quotes an injection phrase can't block a legitimate question. It is a regex heuristic: a paraphrase or another language gets past it. A trained classifier is the upgrade once false negatives matter.

## 7. Error responses

**[SPEC]**
All errors are Spring `ProblemDetail` JSON.
| Status | Cause | `detail` |
|---|---|---|
| 400 | Bean Validation failure, unknown role, file with no text | varies |
| 401 | Bad login; missing, invalid or expired token | `Invalid username or password` (login) |
| 403 | Non-admin upload or delete | |
| 404 | Delete of a missing document, or one outside the caller's roles | `Document not found` |
| 422 | Input guardrail | `The question was blocked because it looks like an attempt to change the assistant's instructions. Please rephrase it.` |
| 422 | Citation guardrail after the re-prompt | `The answer could not be grounded in your documents` |
| 502 | Anthropic or HTTP failure (`LangChain4jException`) | `The language model service failed. Please try again.` |
| 503 | No `ANTHROPIC_API_KEY` | `ANTHROPIC_API_KEY is not set: search works, Ask needs a key` |
| 429 | A rate limit (login, search, upload, Ask, or the global Ask cap) | `Too many requests. Try again in N seconds.` plus a `Retry-After` header |

## 8. Known failure modes

**[BUG] Generic plan skips the HNSW index**
- Symptom: a search that took about 5 ms takes 350 to 425 ms once a connection has run it several times.
- Cause: after 5 executions pgjdbc server-prepares the statement. Postgres can then use a generic plan, which can't see the role array, assumes 1% selectivity for `&&`, and chooses a plan without the HNSW index.
- Fix: `SET plan_cache_mode = force_custom_plan` in Hikari `connection-init-sql`.

**[BUG] Filtered HNSW search returns fewer than k rows**
- Symptom: a user whose roles match a small share of chunks gets fewer than `k` results.
- Cause: pgvector applies the `WHERE` clause after the index scan, which yields at most `hnsw.ef_search` (40) candidates.
- Fix: `SET hnsw.iterative_scan = strict_order` per connection. `RetrievalLatencyIT` fails if any of its 500 queries returns fewer than 6 rows.

**[BUG] Parallel HNSW build fails inside Docker**
- Symptom: building the HNSW index over 100,000 rows fails in the container.
- Cause: a parallel build needs `/dev/shm` at least as large as `maintenance_work_mem` (1 GB); Docker's default is 64 MB.
- Fix: `shm_size: 1gb` in `docker-compose.yml`; the benchmark starts its own container with 1 GB of shared memory.

**[BUG] Citation re-prompt reaches Claude without context**
- Symptom: the re-prompted request has no system prompt and no sources.
- Cause: LangChain4j builds the re-prompt from chat memory only.
- Fix: each call gets its own chat memory under a random `memoryId`, evicted when the call ends. The memory holds the masked message, so the re-prompt stays masked.

**[BUG] Embedding model fails on Alpine images**
- Symptom: the ONNX embedding model does not load in an Alpine-based JRE image.
- Cause: ONNX Runtime needs glibc.
- Fix: the runtime stage uses `eclipse-temurin:21-jre`, which is glibc-based.

**[BUG] `mvnw` fails in the Docker build after a Windows checkout**
- Symptom: the backend build stage cannot run `./mvnw`.
- Cause: CRLF line endings and lost execute bits from a Windows checkout.
- Fix: the Dockerfile runs `sed -i 's/\r$//' mvnw && chmod +x mvnw` before using it.

**[BUG] Role lists contain `FACTOR_PASSWORD`**
- Symptom: a user's role list includes an authority that is not a role.
- Cause: Spring Security 7 adds a `FACTOR_PASSWORD` authority after password login.
- Fix: `Roles.of` keeps only `ROLE_*` authorities and strips the prefix.

## 9. Changelog

**[SPEC]**
- 1.0.0 (2026-09-27): first version, written alongside the README.
