package com.nutribot.nutribot.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nutribot.nutribot.dto.NutritionResponse;
import com.nutribot.nutribot.models.FoodLog;
import com.nutribot.nutribot.models.User;
import com.nutribot.nutribot.enums.LogSource;
import com.nutribot.nutribot.enums.MealType;
import com.nutribot.nutribot.repositories.FoodLogRepository;
import com.nutribot.nutribot.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FoodLogService {

    private final FoodLogRepository foodLogRepository;
    private final UserRepository userRepository;
    private final GeminiService geminiService;
    private final ConversationContextService conversationContextService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Analysis (parse only, do NOT save) ────────────────────────────────────

    /**
     * Calls Gemini with conversation context and parses the JSON response.
     * Throws RuntimeException with a localized message if parsing fails.
     * Does NOT write to the database — caller must confirm via confirmSave().
     */
    @Transactional(readOnly = true)
    public NutritionResponse parseText(Long telegramId, String description) {
        User user = getUser(telegramId);
        List<String> context = conversationContextService.getContext(telegramId);
        String json = geminiService.ask(description, context);
        return parseOrThrow(json, user);
    }

    /**
     * Sends image bytes to Gemini vision and parses the JSON response.
     * Throws RuntimeException with a localized message if parsing fails.
     * Does NOT write to the database — caller must confirm via confirmSave().
     */
    @Transactional(readOnly = true)
    public NutritionResponse parsePhoto(Long telegramId, byte[] imageBytes) {
        User user = getUser(telegramId);
        String json = geminiService.analyzePhoto(imageBytes);
        return parseOrThrow(json, user);
    }

    // ── Persist confirmed entry ────────────────────────────────────────────────

    /**
     * Persists a previously-parsed NutritionResponse as a FoodLog, pushes the
     * food name to the conversation context, then returns a localized daily summary.
     */
    @Transactional
    public String confirmSave(Long telegramId, NutritionResponse nutrition,
                              LogSource logSource, String description) {
        User user = getUser(telegramId);

        FoodLog log = new FoodLog();
        log.setUser(user);
        log.setDescription(description);
        log.setGrams(nutrition.getGrams());
        log.setCalories(nutrition.getCalories());
        log.setProtein(nutrition.getProtein());
        log.setCarbs(nutrition.getCarbs());
        log.setFat(nutrition.getFat());
        log.setSource(logSource);
        log.setMealType(MealType.SNACK);
        foodLogRepository.save(log);

        // Push to rolling context so future references can be resolved
        String contextEntry = String.format("%s (%.0fg)",
                nutrition.getFoodName(),
                nutrition.getGrams() != null ? nutrition.getGrams() : 0);
        conversationContextService.push(telegramId, contextEntry);

        List<FoodLog> todayLogs = getTodayLogs(user.getId());
        double totalCalories = todayLogs.stream().mapToDouble(FoodLog::getCalories).sum();
        double totalProtein  = todayLogs.stream().mapToDouble(FoodLog::getProtein).sum();
        double totalCarbs    = todayLogs.stream().mapToDouble(FoodLog::getCarbs).sum();
        double totalFat      = todayLogs.stream().mapToDouble(FoodLog::getFat).sum();

        return buildSummary(user, nutrition, totalCalories, totalProtein, totalCarbs, totalFat);
    }

    // ── Meal suggestions ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public String getSuggestions(Long telegramId, String userMessage) {
        User user = getUser(telegramId);
        List<FoodLog> todayLogs = getTodayLogs(user.getId());

        double loggedCalories = todayLogs.stream().mapToDouble(FoodLog::getCalories).sum();
        double loggedProtein  = todayLogs.stream().mapToDouble(FoodLog::getProtein).sum();
        double loggedCarbs    = todayLogs.stream().mapToDouble(FoodLog::getCarbs).sum();
        double loggedFat      = todayLogs.stream().mapToDouble(FoodLog::getFat).sum();

        double remainingCalories = safeGoal(user.getCalorieGoal()) - loggedCalories;
        double remainingProtein  = safeGoal(user.getProteinGoal()) - loggedProtein;
        double remainingCarbs    = safeGoal(user.getCarbGoal())    - loggedCarbs;
        double remainingFat      = safeGoal(user.getFatGoal())     - loggedFat;

        return geminiService.suggestMeals(userMessage,
                remainingCalories, remainingProtein, remainingCarbs, remainingFat);
    }

    // ── User language lookup ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public String getUserLanguage(Long telegramId) {
        return userRepository.findByTelegramId(telegramId)
                .map(User::getLanguage)
                .orElse("EN");
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private User getUser(Long telegramId) {
        return userRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new RuntimeException("User not registered. Send /start to register."));
    }

    private NutritionResponse parseOrThrow(String json, User user) {
        try {
            return objectMapper.readValue(json, NutritionResponse.class);
        } catch (Exception e) {
            throw new RuntimeException(isRU(user)
                    ? "Не удалось распознать еду. Попробуйте уточнить, например: «200г куриной грудки»."
                    : "Sorry, I couldn't parse that food. Try being more specific, e.g. '200g chicken breast'.");
        }
    }

    private String buildSummary(User user, NutritionResponse nutrition,
                                double totalCalories, double totalProtein,
                                double totalCarbs, double totalFat) {
        if (isRU(user)) {
            return String.format("""
                    Записано: %s (%.0fг)
                    Калории: %.0f ккал | Белки: %.0fг | Углеводы: %.0fг | Жиры: %.0fг

                    Итого за сегодня:
                    Калории: %.0f / %.0f ккал
                    Белки: %.0fг / %.0fг
                    Углеводы: %.0fг / %.0fг
                    Жиры: %.0fг / %.0fг
                    """,
                    nutrition.getFoodName(), nutrition.getGrams(),
                    nutrition.getCalories(), nutrition.getProtein(), nutrition.getCarbs(), nutrition.getFat(),
                    totalCalories, safeGoal(user.getCalorieGoal()),
                    totalProtein,  safeGoal(user.getProteinGoal()),
                    totalCarbs,    safeGoal(user.getCarbGoal()),
                    totalFat,      safeGoal(user.getFatGoal()));
        } else {
            return String.format("""
                    Logged: %s (%.0fg)
                    Calories: %.0f kcal | Protein: %.0fg | Carbs: %.0fg | Fat: %.0fg

                    Today's total:
                    Calories: %.0f / %.0f kcal
                    Protein: %.0fg / %.0fg
                    Carbs: %.0fg / %.0fg
                    Fat: %.0fg / %.0fg
                    """,
                    nutrition.getFoodName(), nutrition.getGrams(),
                    nutrition.getCalories(), nutrition.getProtein(), nutrition.getCarbs(), nutrition.getFat(),
                    totalCalories, safeGoal(user.getCalorieGoal()),
                    totalProtein,  safeGoal(user.getProteinGoal()),
                    totalCarbs,    safeGoal(user.getCarbGoal()),
                    totalFat,      safeGoal(user.getFatGoal()));
        }
    }

    private List<FoodLog> getTodayLogs(Long userId) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay   = startOfDay.plusDays(1);
        return foodLogRepository.findByUserIdAndLoggedAtBetween(userId, startOfDay, endOfDay);
    }

    private static boolean isRU(User user) {
        return "RU".equals(user.getLanguage());
    }

    private static double safeGoal(Double goal) {
        return goal != null ? goal : 0;
    }
}
