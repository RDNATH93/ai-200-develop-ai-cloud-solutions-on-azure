package com.example.vectorsearch.model;

import java.util.List;

public record Query(String id, String description,List<Double> embedding) {
    
}
