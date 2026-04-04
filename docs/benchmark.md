# Retrieval latency

Reproduce: `cd backend && ./mvnw test -Pbenchmark` (Docker required, about 2 minutes). Source: [`RetrievalLatencyIT.java`](../backend/src/test/java/com/harshpahurkar/rag/search/RetrievalLatencyIT.java).

Run 2026-04-04. 500 queries after 50 warm-up queries, k = 6, user roles [ENGINEERING, EMPLOYEE].

| Stage | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |
|---|---:|---:|---:|---:|
| Total (retrievalMs) | 34.0 | 52.0 | 71.0 | 88.0 |
| Embed query (BGE-small-en-v1.5 q, in-process) | 17.3 | 34.0 | 46.5 | 62.5 |
| SQL (role-filtered HNSW search, one round trip) | 13.4 | 21.0 | 26.9 | 31.5 |
| DB round trip (`SELECT 1`) | 8.0 | 12.4 | 17.0 | 25.1 |

Total is `retrievalMs` from `Retriever.retrieve`, in whole ms. After each retrieve, the embed, the SQL and
a bare `SELECT 1` are timed alone with `System.nanoTime`, in that order. The SQL is one autocommit round trip
(session settings come from Hikari's connection-init-sql); the EXPLAIN below shows how much of it Postgres spends executing.

## Dataset

- 100000 chunks across 1000 documents; 29.2% of chunks are readable by [ENGINEERING, EMPLOYEE]
- random unit-normalized 384-d vectors, chunk text of about 1,000 characters
- load 7.0 s; HNSW build 32.4 s (`maintenance_work_mem = 1GB`, after the load); VACUUM ANALYZE 0.2 s
- chunk table (heap + TOAST) 219 MB; `chunk_embedding_hnsw` 195 MB
- PostgreSQL 18.1 (Debian 18.1-1.pgdg12+2), pgvector 0.8.1, shared_buffers 128MB, hnsw.ef_search 40, hnsw.iterative_scan strict_order

## Machine

- JVM: 20 available processors, Windows 11 (amd64), Java 21.0.12.1
- Docker: 10 CPUs, 8.3 GB memory (Docker Desktop)
- CPU model: Intel Core i7-12700H (14 cores, 20 threads), 16 GB RAM, Windows 11, Docker Desktop (WSL2)

## EXPLAIN (ANALYZE, BUFFERS) of the search, as Retriever runs it

```
Limit  (cost=692.92..720.81 rows=6 width=610) (actual time=2.619..2.708 rows=6.00 loops=1)
  Buffers: shared hit=1320 read=380
  ->  Nested Loop  (cost=692.92..135966.99 rows=29100 width=610) (actual time=2.618..2.706 rows=6.00 loops=1)
        Buffers: shared hit=1320 read=380
        ->  Index Scan using chunk_embedding_hnsw on chunk c  (cost=692.64..132954.00 rows=100000 width=590) (actual time=2.561..2.596 rows=12.00 loops=1)
              Order By: (embedding <=> '[384 floats]'::vector)
              Index Searches: 1
              Buffers: shared hit=1257 read=371
        ->  Memoize  (cost=0.29..0.31 rows=1 width=30) (actual time=0.004..0.004 rows=0.50 loops=12)
              Cache Key: c.document_id
              Cache Mode: logical
              Hits: 0  Misses: 12  Evictions: 0  Overflows: 0  Memory Usage: 2kB
              Buffers: shared hit=36
              ->  Index Scan using document_pkey on document d  (cost=0.28..0.30 rows=1 width=30) (actual time=0.003..0.003 rows=0.50 loops=12)
                    Index Cond: (id = c.document_id)
                    Filter: (allowed_roles && '{ENGINEERING,EMPLOYEE}'::text[])
                    Rows Removed by Filter: 0
                    Index Searches: 12
                    Buffers: shared hit=36
Planning:
  Buffers: shared hit=13
Planning Time: 0.293 ms
Execution Time: 2.734 ms
```
