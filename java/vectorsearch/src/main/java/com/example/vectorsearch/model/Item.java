package com.example.vectorsearch.model;

import java.util.List;

public record Item(
    String id,
    String documentId,
    String content,
    MetaData metadata,
    List<Double> embedding,
    String createdAt,
    int chunkIndex
) {}
