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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public String logFood(Long telegramId, String description) {
        User user = userRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new RuntimeException("User not registered. Send /start to register."));

        String json = geminiService.ask(description);

        NutritionResponse nutrition;
        try {
            nutrition = objectMapper.readValue(json, NutritionResponse.class);
        } catch (Exception e) {
            return "Sorry, I couldn't parse that food. Try being more specific, e.g. '200g chicken breast'.";
        }

        FoodLog log = new FoodLog();
        log.setUser(user);
        log.setDescription(description);
        log.setGrams(nutrition.getGrams());
        log.setCalories(nutrition.getCalories());
        log.setProtein(nutrition.getProtein());
        log.setCarbs(nutrition.getCarbs());
        log.setFat(nutrition.getFat());
        log.setSource(LogSource.AI_DECOMPOSED);
        log.setMealType(MealType.SNACK);

        foodLogRepository.save(log);

        List<FoodLog> todayLogs = getTodayLogs(user.getId());
        double totalCalories = todayLogs.stream().mapToDouble(FoodLog::getCalories).sum();
        double totalProtein = todayLogs.stream().mapToDouble(FoodLog::getProtein).sum();
        double totalCarbs = todayLogs.stream().mapToDouble(FoodLog::getCarbs).sum();
        double totalFat = todayLogs.stream().mapToDouble(FoodLog::getFat).sum();

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
                totalCalories, user.getCalorieGoal(),
                totalProtein, user.getProteinGoal(),
                totalCarbs, user.getCarbGoal(),
                totalFat, user.getFatGoal()
        );
    }

    private List<FoodLog> getTodayLogs(Long userId) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = startOfDay.plusDays(1);
        return foodLogRepository.findByUserIdAndLoggedAtBetween(userId, startOfDay, endOfDay);
    }
}