package com.nutribot.nutribot.services;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class GeminiService {

    private final String apiKey;
    private final RestClient restClient;

    public GeminiService(@Value("${gemini.api.key}") String apiKey) {
        this.apiKey = apiKey;
        this.restClient = RestClient.create();
    }

    public String ask(String prompt) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + apiKey;

        String systemPrompt = """
                You are a nutrition analysis API. Your only job is to analyze food descriptions and return nutritional data.
                When given any food description, respond ONLY with a raw JSON object. No markdown, no code blocks, no explanation, no extra text — just the JSON.
                Required format:
                {
                "foodName":"string",
                "grams":number,
                "calories":number,
                "protein":number,
                "carbs":number,
                "fat":number
                }
                Estimate all values based on the portion size described. If no weight is given, estimate a typical serving.
    """;

        Map<String, Object> body = Map.of(
                "system_instruction", Map.of(
                        "parts", List.of(Map.of("text", systemPrompt))
                ),
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", prompt)
                        ))
                )
        );

        Map response = restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        return extractText(response);
    }

    private String extractText(Map response) {
        try {
            var candidates = (List) response.get("candidates");
            var content = (Map) ((Map) candidates.get(0)).get("content");
            var parts = (List) content.get("parts");
            return (String) ((Map) parts.get(0)).get("text");
        } catch (Exception e) {
            return "Sorry, I couldn't process that.";
        }
    }
}