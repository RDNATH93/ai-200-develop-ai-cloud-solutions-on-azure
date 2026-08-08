package com.example.ragwithcosmos.pojo;

public record Chunk(
    String id,
    String documentId,
    String content,
    Metadata metadata,
    int chunkIndex,
    String createdAt
) {
    
}
