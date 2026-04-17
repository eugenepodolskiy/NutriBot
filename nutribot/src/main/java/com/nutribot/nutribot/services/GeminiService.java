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
     * Returns a JSON object including fiber estimate.
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
                "fat":number,
                "fiber":number
                }
                Estimate all values based on the portion size described. If no weight is given, estimate a typical serving.
                Estimate fiber content from typical values for that food. If unknown, use 0.
                Detect the language of the user message and respond in that same language. JSON field names must always remain in English.
                """ + contextSection;
        return callGemini(systemPrompt, prompt);
    }

    // ── Photo-based food analysis ──────────────────────────────────────────────

    public String analyzePhoto(byte[] imageBytes, String hint) {
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
                "fat":number,
                "fiber":number
                }
                Estimate all values based on the portion visible in the image. If unclear, estimate a typical serving.
                Estimate fiber content from typical values for that food. If unknown, use 0.
                JSON field names must always remain in English.
                """;
        String textPrompt = "Identify the food in this image and estimate the portion size and nutritional content.";
        if (hint != null && !hint.isBlank()) {
            textPrompt += " The user described this food as: " + hint.trim() +
                    ". Use this to help identify the food and estimate the portion size more accurately.";
        }
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
     * Classifies the user's intent. Returns one of:
     * FOOD_LOG, SUGGESTION, CORRECTION, EDIT_LOG, HISTORY, SUPPLEMENT, PROFILE_UPDATE, UNCLEAR.
     * Falls back to FOOD_LOG on unexpected or empty response.
     */
    public String detectIntent(String text, String userLanguage) {
        String systemPrompt = """
                Classify the intent of this message from a nutrition tracking app user.
                Return exactly one word from the following list:
                  FOOD_LOG       — user is describing food they ate or drank
                  SUGGESTION     — user is asking for meal suggestions or what to eat next
                  CORRECTION     — user wants to correct or change a pending food entry
                  EDIT_LOG       — user wants to edit or update their most recent logged food entry
                  HISTORY        — user wants to see their food history, past logs, or a summary of previous days
                  SUPPLEMENT     — user is asking about supplements or vitamins (add, list, delete, mark taken, reminders)
                  PROFILE_UPDATE — user wants to update their profile (weight, height, age, sex, activity level, or goal)
                  UNCLEAR        — the message has nothing to do with food, nutrition, or profile management
                Return only the single word, nothing else.
                """;
        String userPrompt = "User language: " + userLanguage + "\nMessage: " + text;
        String result = callGemini(systemPrompt, userPrompt).trim().toUpperCase();
        return switch (result) {
            case "FOOD_LOG", "SUGGESTION", "CORRECTION", "EDIT_LOG",
                    "HISTORY", "SUPPLEMENT", "PROFILE_UPDATE", "UNCLEAR" -> result;
            default -> "FOOD_LOG";
        };
    }

    // ── Supplement action classification ──────────────────────────────────────

    /**
     * Classifies a supplement-related message into one of:
     * ADD, LIST, DELETE, MARK_TAKEN, MARK_UNTAKEN.
     * Falls back to LIST.
     */
    public String classifySupplementAction(String text) {
        String systemPrompt = """
                The user is managing their supplement/vitamin schedule in a nutrition tracking app.
                Classify their message as exactly one of these words:

                  ADD          — user wants to CREATE a new supplement entry in their tracking schedule.
                                 Trigger words: "add", "добавь", "начать принимать", "хочу принимать",
                                 "поставь напоминание", "start taking", "track", "remind me to take".
                                 Examples: "Add vitamin D 1000 IU at 8am", "добавь омегу 3",
                                 "хочу принимать кальций каждый день", "remind me to take magnesium".

                  LIST         — user wants to view their existing supplement list.
                                 Examples: "show my supplements", "мои добавки", "what vitamins do I take".

                  DELETE       — user wants to permanently remove a supplement from their schedule.
                                 Examples: "remove vitamin C", "удали омегу", "stop tracking zinc".

                  MARK_TAKEN   — user is confirming they ALREADY CONSUMED a supplement that is in their list.
                                 Trigger words: "took", "принял", "выпил", "just had", "I took".
                                 Examples: "I took my vitamin D", "выпил омегу 3 и кальций",
                                 "just had magnesium", "принял витамин".

                  MARK_UNTAKEN — user says they did NOT take a supplement, or wants to undo a taken mark.
                                 Trigger words: "didn't take", "не выпил", "не принял", "uncheck", "undo".
                                 Examples: "I didn't take magnesium", "не выпил кальций", "uncheck omega 3".

                KEY RULE: ADD = registering a supplement for future tracking. MARK_TAKEN = recording consumption
                of a supplement that already exists in the schedule. When in doubt, prefer ADD for messages
                that contain "добавь", "add", "начать", "start", "remind".

                Return only the single word, nothing else.
                """;
        String result = callGemini(systemPrompt, text).trim().toUpperCase();
        return switch (result) {
            case "ADD", "LIST", "DELETE", "MARK_TAKEN", "MARK_UNTAKEN" -> result;
            default -> "LIST";
        };
    }

    /**
     * Extracts a JSON array of supplement/vitamin names mentioned in the message.
     * Returns a raw JSON array string, e.g. ["Vitamin D","Omega-3"].
     * Returns "[]" if none found.
     */
    public String extractSupplementNames(String text) {
        String systemPrompt = """
                Extract all supplement and vitamin names mentioned in this message.
                Respond ONLY with a raw JSON array of strings. No markdown, no explanation, no extra text.
                Examples:
                  Input: "I took calcium, omega 3 and vitamin D"
                  Output: ["calcium","omega 3","vitamin D"]
                  Input: "выпил магний"
                  Output: ["магний"]
                  Input: "took my vitamins"
                  Output: ["vitamins"]
                If no supplement names can be identified, return: []
                """;
        return callGemini(systemPrompt, text);
    }

    /**
     * Extracts the supplement details from a natural-language "add supplement" request.
     * Returns a JSON object: {"name":"...","dose":number,"unit":"...","reminderTimes":"HH:mm,HH:mm"}
     * reminderTimes may be empty string if not mentioned.
     */
    public String extractSupplementDetails(String text) {
        String systemPrompt = """
                Extract supplement/vitamin details from the user's message.
                Respond ONLY with a raw JSON object, no markdown, no explanation:
                {
                "name":"string",
                "dose":number_or_null,
                "unit":"mg|mcg|g|IU|string or empty",
                "reminderTimes":"HH:mm,HH:mm or empty string"
                }
                If dose or unit is not mentioned, use null/empty. Convert time mentions to 24-hour HH:mm format.
                """;
        return callGemini(systemPrompt, text);
    }

    /**
     * Extracts a user profile field update from natural language.
     * Returns JSON: {"field":"weight|height|age|sex|activity|goal","value":"string"}
     */
    public String extractProfileUpdate(String text) {
        String systemPrompt = """
                The user wants to update their nutrition profile. Extract what they want to change.
                Respond ONLY with a raw JSON object:
                {
                "field":"weight|height|age|sex|activity|goal",
                "value":"string"
                }
                field must be exactly one of: weight, height, age, sex, activity, goal.
                value is the new value as a string.
                """;
        return callGemini(systemPrompt, text);
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
