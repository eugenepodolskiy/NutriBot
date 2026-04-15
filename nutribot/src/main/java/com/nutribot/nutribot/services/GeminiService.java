package com.nutribot.nutribot.services;

import com.nutribot.nutribot.enums.ActivityLevel;
import com.nutribot.nutribot.enums.GoalType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class GeminiService {

    private final String apiKey;
    private final RestClient restClient;

    public GeminiService(@Value("${gemini.api.key}") String apiKey) {
        this.apiKey = apiKey;
        this.restClient = RestClient.create();
    }

    // ── Text-based food analysis ───────────────────────────────────────────────

    /** Convenience overload — no conversation context. */
    public String ask(String prompt) {
        return ask(prompt, List.of());
    }

    /**
     * Analyzes a food description with optional conversation context.
     * If recentContext is non-empty its entries are appended to the system prompt
     * so Gemini can resolve references like "the same thing as before".
     */
    public String ask(String prompt, List<String> recentContext) {
        String contextSection = recentContext.isEmpty() ? "" :
                "\nThe user recently ate: " + String.join(", ", recentContext) +
                ". If they reference something they ate before, use this context to resolve what they mean.";

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
                Detect the language of the user message and respond in that same language. JSON field names must always remain in English.
                """ + contextSection;
        return callGemini(systemPrompt, prompt);
    }

    // ── Photo-based food analysis ──────────────────────────────────────────────

    public String analyzePhoto(byte[] imageBytes) {
        String systemPrompt = """
                You are a nutrition analysis API. Your only job is to analyze food visible in images and return nutritional data.
                Respond ONLY with a raw JSON object. No markdown, no code blocks, no explanation, no extra text — just the JSON.
                Required format:
                {
                "foodName":"string",
                "grams":number,
                "calories":number,
                "protein":number,
                "carbs":number,
                "fat":number
                }
                Estimate all values based on the portion visible in the image. If unclear, estimate a typical serving.
                JSON field names must always remain in English.
                """;
        String textPrompt = "Identify the food in this image and estimate the portion size and nutritional content.";
        return callGeminiWithMedia(systemPrompt, imageBytes, "image/jpeg", textPrompt);
    }

    // ── Voice transcription ────────────────────────────────────────────────────

    public String transcribeAudio(byte[] audioBytes) {
        String textPrompt = "Transcribe this audio message exactly as spoken. Return only the transcription, nothing else.";
        String result = callGeminiWithMedia(null, audioBytes, "audio/ogg", textPrompt);
        log.info("TRANSCRIBE raw bytes length: {}", result.length());
        log.info("TRANSCRIBE result: {}", result);
        return result;
    }

    // ── Meal suggestions ───────────────────────────────────────────────────────

    public String suggestMeals(String userMessage,
                               double remainingCalories, double remainingProtein,
                               double remainingCarbs, double remainingFat) {
        String systemPrompt = """
                You are a helpful nutrition assistant. Suggest 2-3 meal ideas that fit within the user's remaining daily macros.
                Respond in plain text, in the same language as the user's message. Do not return JSON.
                """;
        String userPrompt = String.format("""
                Remaining macros for today:
                Calories: %.0f kcal
                Protein: %.0f g
                Carbs: %.0f g
                Fat: %.0f g

                User's message: %s

                Suggest 2-3 meals or snacks that fit within these remaining macros.
                """, remainingCalories, remainingProtein, remainingCarbs, remainingFat, userMessage);
        return callGemini(systemPrompt, userPrompt);
    }

    // ── Intent detection ──────────────────────────────────────────────────────

    /**
     * Classifies the user's intent. Returns one of: FOOD_LOG, SUGGESTION, CORRECTION, UNCLEAR.
     * Falls back to FOOD_LOG on unexpected or empty response.
     */
    public String detectIntent(String text, String userLanguage) {
        String systemPrompt = """
                Classify the intent of this message from a nutrition tracking app user.
                Return exactly one word: FOOD_LOG if they are describing food they ate, SUGGESTION if they are asking for meal suggestions or what to eat, CORRECTION if they are correcting a previous entry, UNCLEAR if the message has nothing to do with food or nutrition.
                Return only the word, nothing else.
                """;
        String userPrompt = "User language: " + userLanguage + "\nMessage: " + text;
        String result = callGemini(systemPrompt, userPrompt).trim().toUpperCase();
        return switch (result) {
            case "FOOD_LOG", "SUGGESTION", "CORRECTION", "UNCLEAR" -> result;
            default -> "FOOD_LOG";
        };
    }

    // ── Classification helpers ─────────────────────────────────────────────────

    public ActivityLevel classifyActivity(String description) {
        String systemPrompt = """
                You are an activity level classifier.
                Based on the user's description of their daily routine, classify their activity level as exactly one of:
                SEDENTARY, LIGHT, MODERATE, ACTIVE

                Definitions:
                SEDENTARY — desk job or mostly sitting, little to no exercise
                LIGHT — light exercise 1–3 days/week, or a job that involves some walking
                MODERATE — moderate exercise 3–5 days/week
                ACTIVE — hard exercise 6–7 days/week, or a very physically demanding job

                Respond with ONLY the single uppercase word. No punctuation, no explanation.
                """;
        String result = callGemini(systemPrompt, description).trim().toUpperCase();
        try {
            return ActivityLevel.valueOf(result);
        } catch (IllegalArgumentException e) {
            return ActivityLevel.MODERATE;
        }
    }

    public GoalType classifyGoal(String description) {
        String systemPrompt = """
                Based on this description of a person's fitness goal, return exactly one word: LOSE, MAINTAIN, or GAIN.
                Return only the word, nothing else.
                """;
        String result = callGemini(systemPrompt, description).trim().toUpperCase();
        try {
            return GoalType.valueOf(result);
        } catch (IllegalArgumentException e) {
            return GoalType.MAINTAIN;
        }
    }

    // ── Private HTTP helpers ───────────────────────────────────────────────────

    private String callGemini(String systemPrompt, String userPrompt) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + apiKey;

        Map<String, Object> body = Map.of(
                "system_instruction", Map.of(
                        "parts", List.of(Map.of("text", systemPrompt))
                ),
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", userPrompt)
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

    /**
     * Sends a multimodal request containing an inline media part (image or audio)
     * alongside a text prompt. systemPrompt may be null for requests that need no
     * system instruction (e.g. plain audio transcription).
     */
    private String callGeminiWithMedia(String systemPrompt, byte[] mediaBytes,
                                       String mimeType, String textPrompt) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + apiKey;

        String base64Data = Base64.getEncoder().encodeToString(mediaBytes);

        Map<String, Object> inlineDataPart = Map.of(
                "inlineData", Map.of("mimeType", mimeType, "data", base64Data)
        );
        Map<String, Object> textPart = Map.of("text", textPrompt);

        Map<String, Object> body = new HashMap<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system_instruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
        }
        body.put("contents", List.of(Map.of("parts", List.of(inlineDataPart, textPart))));

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
            return "";
        }
    }
}
