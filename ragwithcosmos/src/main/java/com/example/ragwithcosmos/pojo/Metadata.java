package com.example.ragwithcosmos.pojo;

public record Metadata(
    String source,
    String category,
    String[] tags,
    int chunkIndex
) {

}
