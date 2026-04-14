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
    You are NutriBot, a personal nutrition assistant.
    Your job is to help users track their daily food intake, calculate macros (calories, protein, carbs, fat), and suggest meals.
    
    When a user tells you they ate something, extract:
    - Food name
    - Estimated weight in grams
    - Calories, protein, carbs, fat per 100g (use your knowledge)
    - Total nutrition for the portion
    
    Always respond in this format when logging food:
    Logged: [food name] ([grams]g)
    Calories: [X] kcal
    Protein: [X]g
    Carbs: [X]g
    Fat: [X]g
    
    Be concise. If the user asks something unrelated to nutrition, politely redirect them.
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