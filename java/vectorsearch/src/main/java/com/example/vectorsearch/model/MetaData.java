package com.example.vectorsearch.model;

import java.util.List;

public record MetaData(
    String source,
    String category,
    List<String> tags,
    int chunkIndex
) {
    
}
