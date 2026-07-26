CREATE INDEX ON text_chunk_vector
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 200);