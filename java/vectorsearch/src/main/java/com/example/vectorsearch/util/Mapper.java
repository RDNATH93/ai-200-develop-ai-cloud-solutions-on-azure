package com.example.vectorsearch.util;

import java.util.List;

import com.example.vectorsearch.dto.QueryDTO;
import com.example.vectorsearch.model.Query;

/**
 * Mapper
 */
public class Mapper {

    public static List<QueryDTO>mapQueriesToQueryDTO(List<Query> queries){
       return queries.stream().map(q-> new QueryDTO(q.id(), q.description())).toList();
    }
}