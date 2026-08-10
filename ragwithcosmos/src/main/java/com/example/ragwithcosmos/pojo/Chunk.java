package com.example.ragwithcosmos.pojo;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Chunk(
    @JsonProperty("chunk_id") String id,
    @JsonProperty("document_id") String documentId,
    String content,
    Metadata metadata,
    List<String> embedding,
    int chunkIndex,
    String createdAt
) {
    
}
