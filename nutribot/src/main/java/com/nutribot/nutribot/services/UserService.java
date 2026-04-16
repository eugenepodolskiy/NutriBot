package com.nutribot.nutribot.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nutribot.nutribot.enums.ActivityLevel;
import com.nutribot.nutribot.enums.GoalType;
import com.nutribot.nutribot.enums.Sex;
import com.nutribot.nutribot.models.User;
import com.nutribot.nutribot.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final GeminiService  geminiService;
    private final ObjectMapper   objectMapper = new ObjectMapper();

    // ── Main entry point from NutriBot ─────────────────────────────────────────

    @Transactional
    public String handleProfileUpdate(Long telegramId, String text, String lang) {
        User user = getUser(telegramId);
        String json = geminiService.extractProfileUpdate(text);
        try {
            Map<?, ?> parsed = objectMapper.readValue(json, Map.class);
            String field = (String) parsed.get("field");
            String value = (String) parsed.get("value");
            if (field == null || value == null) return unknownUpdate(lang);
            return updateProfile(telegramId, field.toLowerCase().trim(), value.trim(), lang, user);
        } catch (Exception e) {
            log.error("[UserService] handleProfileUpdate parse error: {}", e.getMessage(), e);
            return unknownUpdate(lang);
        }
    }

    // ── Atomic field update + goal recalculation ───────────────────────────────

    @Transactional
    public String updateProfile(Long telegramId, String field, String value) {
        User user = getUser(telegramId);
        String lang = user.getLanguage();
        return updateProfile(telegramId, field, value, lang, user);
    }

    private String updateProfile(Long telegramId, String field, String value,
                                 String lang, User user) {
        try {
            switch (field) {
                case "weight" -> user.setWeightKg(Double.parseDouble(value));
                case "height" -> user.setHeightCm(Double.parseDouble(value));
                case "age"    -> user.setAge(Integer.parseInt(value));
                case "sex"    -> {
                    String v = value.toLowerCase();
                    if (v.contains("male") || v.contains("man") || v.contains("мужской") || v.contains("мужчина")) {
                        user.setSex(Sex.MALE);
                    } else if (v.contains("female") || v.contains("woman") || v.contains("женский") || v.contains("женщина")) {
                        user.setSex(Sex.FEMALE);
                    } else {
                        return isRU(lang) ? "Укажите мужской или женский." : "Please specify male or female.";
                    }
                }
                case "activity" -> user.setActivityLevel(geminiService.classifyActivity(value));
                case "goal"     -> user.setGoalType(safeGoalType(value));
                default -> { return unknownUpdate(lang); }
            }
        } catch (NumberFormatException e) {
            return isRU(lang) ? "Некорректное значение: " + value : "Invalid value: " + value;
        }

        recalculateGoals(user);
        userRepository.save(user);

        return isRU(lang)
                ? String.format("Профиль обновлён!\n\nНовые цели:\nКалории: %.0f ккал\nБелки: %.0fг\nУглеводы: %.0fг\nЖиры: %.0fг\nКлетчатка: %.0fг\nВода: %.0f мл/день",
                        user.getCalorieGoal(), user.getProteinGoal(), user.getCarbGoal(),
                        user.getFatGoal(), user.getFiberGoal(), user.getWaterGoal())
                : String.format("Profile updated!\n\nNew goals:\nCalories: %.0f kcal\nProtein: %.0fg\nCarbs: %.0fg\nFat: %.0fg\nFiber: %.0fg\nWater: %.0f ml/day",
                        user.getCalorieGoal(), user.getProteinGoal(), user.getCarbGoal(),
                        user.getFatGoal(), user.getFiberGoal(), user.getWaterGoal());
    }

    // ── Goal recalculation (same Mifflin-St Jeor as OnboardingService) ─────────

    private void recalculateGoals(User user) {
        if (user.getWeightKg() == null || user.getHeightCm() == null
                || user.getAge() == null || user.getActivityLevel() == null
                || user.getGoalType() == null) return;

        double bmr;
        if (user.getSex() == Sex.MALE) {
            bmr = 10 * user.getWeightKg() + 6.25 * user.getHeightCm() - 5 * user.getAge() + 5;
        } else if (user.getSex() == Sex.FEMALE) {
            bmr = 10 * user.getWeightKg() + 6.25 * user.getHeightCm() - 5 * user.getAge() - 161;
        } else {
            bmr = 10 * user.getWeightKg() + 6.25 * user.getHeightCm() - 5 * user.getAge() - 78;
        }

        double multiplier = switch (user.getActivityLevel()) {
            case SEDENTARY -> 1.2;
            case LIGHT     -> 1.375;
            case MODERATE  -> 1.55;
            case ACTIVE    -> 1.725;
        };

        double tdee = bmr * multiplier;
        double calorieGoal = switch (user.getGoalType()) {
            case LOSE     -> tdee * 0.85;
            case MAINTAIN -> tdee;
            case GAIN     -> tdee * 1.10;
        };

        double proteinGoal = switch (user.getGoalType()) {
            case LOSE     -> user.getWeightKg() * 2.2;
            case MAINTAIN -> user.getWeightKg() * 1.8;
            case GAIN     -> user.getWeightKg() * 2.5;
        };

        double fatGoal  = (calorieGoal * 0.30) / 9;
        double carbGoal = Math.max((calorieGoal - proteinGoal * 4 - fatGoal * 9) / 4, 0);
        double fiberGoal = (user.getSex() == Sex.MALE) ? 38.0 : 25.0;
        double waterGoal = user.getWeightKg() * 35;

        user.setCalorieGoal(calorieGoal);
        user.setProteinGoal(proteinGoal);
        user.setCarbGoal(carbGoal);
        user.setFatGoal(fatGoal);
        user.setFiberGoal(fiberGoal);
        user.setWaterGoal(waterGoal);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private User getUser(Long telegramId) {
        return userRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new RuntimeException("User not found. Send /start to register."));
    }

    private static GoalType safeGoalType(String value) {
        try {
            return GoalType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return GoalType.MAINTAIN;
        }
    }

    private static String unknownUpdate(String lang) {
        return isRU(lang)
                ? "Не удалось понять, что нужно обновить. Попробуйте: «мой вес теперь 75кг» или «изменить цель на похудение»."
                : "Could not understand what to update. Try: \"my weight is now 75kg\" or \"change goal to lose weight\".";
    }

    private static boolean isRU(String lang) {
        return "RU".equals(lang);
    }
}
