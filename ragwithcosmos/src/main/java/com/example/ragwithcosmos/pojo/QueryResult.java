package com.example.ragwithcosmos.pojo;

import java.util.List;
import java.util.Map;

public record QueryResult(
    String success,
    List<Map>results,
    int count,
    String error
) {}
