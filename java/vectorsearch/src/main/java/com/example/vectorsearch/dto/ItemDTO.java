package com.example.vectorsearch.dto;

import com.example.vectorsearch.model.MetaData;

public record ItemDTO(
    String id,
    String documentId,
    String content,
    MetaData metadata,
    String similarityScore
) {}
