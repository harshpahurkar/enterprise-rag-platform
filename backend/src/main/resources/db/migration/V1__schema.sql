CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE app_user (
    id            bigserial PRIMARY KEY,
    username      text UNIQUE NOT NULL,
    password_hash text NOT NULL,
    roles         text[] NOT NULL
);

CREATE TABLE document (
    id            bigserial PRIMARY KEY,
    title         text NOT NULL,
    filename      text NOT NULL,
    content_type  text,
    -- RBAC: a user may read this document when their roles overlap this list.
    allowed_roles text[] NOT NULL CHECK (cardinality(allowed_roles) > 0),
    uploaded_by   text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE chunk (
    id          bigserial PRIMARY KEY,
    document_id bigint NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    chunk_index int NOT NULL,
    content     text NOT NULL,
    -- 384 = BGE-small-en-v1.5. AiConfig refuses to start if the model disagrees.
    embedding   vector(384) NOT NULL
);

CREATE INDEX chunk_embedding_hnsw ON chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX chunk_document_id ON chunk (document_id);
