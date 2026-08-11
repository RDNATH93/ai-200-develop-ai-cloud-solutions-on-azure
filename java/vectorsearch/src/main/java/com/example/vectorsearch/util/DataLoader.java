package com.example.vectorsearch.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


@Component
public class DataLoader {
    private static final Logger logger = LoggerFactory.getLogger(DataLoader.class);
    
    public List<Map<String, Object>> loadSampleJsonData(String rootPath) {
        try {
            Resource resource = new ClassPathResource("sample_vectors.json");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(resource.getInputStream());
            JsonNode itemsNode = root.path(rootPath);

            if (!itemsNode.isArray()) {
                return List.of();
            }

            List<Map<String, Object>> items = new ArrayList<>();
            for (JsonNode itemNode : itemsNode) {
                if (itemNode.isObject()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    Iterator<Map.Entry<String, JsonNode>> fields = itemNode.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        item.put(field.getKey(), convertJsonNode(field.getValue()));
                    }
                    items.add(item);
                }
            }
            return items;
        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
            return List.of();
        }
    }

    public Object convertJsonNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                map.put(field.getKey(), convertJsonNode(field.getValue()));
            }
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode child : node) {
                list.add(convertJsonNode(child));
            }
            return list;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        return node.asText();
    }
    
}
