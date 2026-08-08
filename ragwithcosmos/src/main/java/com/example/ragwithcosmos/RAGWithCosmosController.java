package com.example.ragwithcosmos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.ragwithcosmos.pojo.Chunk;
import com.example.ragwithcosmos.pojo.QueryResult;
import org.springframework.web.bind.annotation.RequestBody;


@Controller
public class RAGWithCosmosController {
    private static final Logger logger = LoggerFactory.getLogger(RAGWithCosmosController.class);

    private final RAGWithCosmosService ragWithCosmosService;

    RAGWithCosmosController(RAGWithCosmosService ragWithCosmosService) {
        this.ragWithCosmosService = ragWithCosmosService;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("document_ids", ragWithCosmosService.getAllDocumentIds());
        model.addAttribute("categories", ragWithCosmosService.getAllCategories());
        return "index";
    }

    @PostMapping("/get-chunks")
    public String getChunks(@RequestParam("document_id") String documentId, Model model) {
        try {
            logger.info("Fetch chunks for document id {}", documentId);
            List<Chunk> chunks = ragWithCosmosService.getChunksByDocument(documentId);
            List<String> documentIds = ragWithCosmosService.getAllDocumentIds();
            List<String> categories = ragWithCosmosService.getAllCategories();
            model.addAllAttributes(Map.of(
                    "document_ids", documentIds,
                    "categories", categories,
                    "chunks_result", chunks,
                    "chunks_document_id", documentId));
        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
        }
        return "index";
    }

    @PostMapping("/search-metadata")
    public String searchMetadata(@RequestParam("category") String category,
            @RequestParam(name = "tag", required = false) String tag,
            Model model) {

        Map<String, String> filters = new HashMap<>();
        category = category.trim();
        tag = tag.trim();

        if (!category.isBlank()) {
            filters.put("category", category);
        }
        if (!tag.isBlank()) {
            filters.put("tag", tag);
        }
        if (filters.size() < 1) {
            logger.error("At least one filter (category or tag) needed");
            return "redirect:/index";
        }
        try {
            List<Chunk> chunks = ragWithCosmosService.searchChunksByMetadata(filters);
            List<String> documentIds = ragWithCosmosService.getAllDocumentIds();
            List<String> categories = ragWithCosmosService.getAllCategories();

            model.addAllAttributes(Map.of(
                    "document_ids", documentIds,
                    "categories", categories,
                    "search_result", chunks,
                    "search_filters", filters));

        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
        }

        return "index";
    }

    @PostMapping("/execute-query")
    public String runQuery(@RequestParam("sql_query") String sqlQuery,Model model){
        if(sqlQuery.isBlank()){
            logger.error("Query is missing");
            return "redirect:/index";
        }
        QueryResult result = ragWithCosmosService.executeQuery(sqlQuery);
        List<String> documentIds = ragWithCosmosService.getAllDocumentIds();
        List<String> categories = ragWithCosmosService.getAllCategories();

         model.addAllAttributes(Map.of(
             "document_ids", documentIds,
             "categories", categories,
             "query_result",result,
             "executed_query",sqlQuery));
        return "index";
    }

    @PostMapping("/run-tests")
    public String postMethodName(Model model) {
        try {
          var test_results = ragWithCosmosService.runTestWorkflow(); 
          List<String> documentIds = ragWithCosmosService.getAllDocumentIds();
          List<String> categories = ragWithCosmosService.getAllCategories();

        model.addAllAttributes(Map.of(
             "document_ids", documentIds,
             "categories", categories,
             "test_results",test_results));

        } catch (Exception ex) {
            logger.error("Error running tests {}",ex.getMessage(),ex);
            return "redirect:/index";
        }
        return "index";
    }
    


}
