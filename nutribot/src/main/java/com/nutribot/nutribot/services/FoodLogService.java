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
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FoodLogService {

    private final FoodLogRepository foodLogRepository;
    private final UserRepository userRepository;
    private final GeminiService geminiService;
    private final ConversationContextService conversationContextService;
    private final SupplementService supplementService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ── Parse only (no DB write) ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public NutritionResponse parseText(Long telegramId, String description) {
        User user = getUser(telegramId);
        List<String> context = conversationContextService.getContext(telegramId);
        String json = geminiService.ask(description, context);
        return parseOrThrow(json, user);
    }

    @Transactional(readOnly = true)
    public NutritionResponse parsePhoto(Long telegramId, byte[] imageBytes, String caption) {
        User user = getUser(telegramId);
        String json = geminiService.analyzePhoto(imageBytes, caption);
        return parseOrThrow(json, user);
    }

    // ── Persist confirmed entry ────────────────────────────────────────────────

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
        log.setFiber(nutrition.getFiber());
        log.setSource(logSource);
        log.setMealType(MealType.SNACK);
        foodLogRepository.save(log);

        String contextEntry = String.format("%s (%.0fg)",
                nutrition.getFoodName(),
                nutrition.getGrams() != null ? nutrition.getGrams() : 0);
        conversationContextService.push(telegramId, contextEntry);

        List<FoodLog> todayLogs = getTodayLogs(user.getId());
        String summary = buildSummary(user, nutrition, totals(todayLogs), false);
        String suppStatus = supplementService.getTodaySupplementStatus(user, user.getLanguage());
        return suppStatus.isEmpty() ? summary : summary + "\n" + suppStatus;
    }

    // ── Feature 3: Edit last log ───────────────────────────────────────────────

    @Transactional
    public String editLastLog(Long telegramId, String newDescription) {
        User user = getUser(telegramId);
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay   = startOfDay.plusDays(1);

        FoodLog log = foodLogRepository
                .findFirstByUserIdAndLoggedAtBetweenOrderByLoggedAtDesc(user.getId(), startOfDay, endOfDay)
                .orElseThrow(() -> new RuntimeException(isRU(user)
                        ? "Сегодня ещё нет записей для редактирования."
                        : "No entries logged today to edit."));

        List<String> context = conversationContextService.getContext(telegramId);
        String json = geminiService.ask(newDescription, context);
        NutritionResponse nutrition = parseOrThrow(json, user);

        log.setDescription(newDescription);
        log.setGrams(nutrition.getGrams());
        log.setCalories(nutrition.getCalories());
        log.setProtein(nutrition.getProtein());
        log.setCarbs(nutrition.getCarbs());
        log.setFat(nutrition.getFat());
        log.setFiber(nutrition.getFiber());
        foodLogRepository.save(log);

        List<FoodLog> todayLogs = getTodayLogs(user.getId());
        String summary = buildSummary(user, nutrition, totals(todayLogs), true);
        String suppStatus = supplementService.getTodaySupplementStatus(user, user.getLanguage());
        return suppStatus.isEmpty() ? summary : summary + "\n" + suppStatus;
    }

    // ── Feature 3: Recent logs ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public String getRecentLogs(Long telegramId) {
        User user = getUser(telegramId);
        List<FoodLog> logs = foodLogRepository.findTop5ByUserIdOrderByLoggedAtDesc(user.getId());

        if (logs.isEmpty()) {
            return isRU(user) ? "Записей пока нет." : "No food entries yet.";
        }

        DateTimeFormatter dtFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm");
        StringBuilder sb = new StringBuilder();
        sb.append(isRU(user) ? "Последние записи:\n" : "Recent entries:\n");
        for (int i = 0; i < logs.size(); i++) {
            FoodLog l = logs.get(i);
            sb.append(String.format("%d. %s — %s — %.0f kcal\n",
                    i + 1,
                    l.getLoggedAt().format(dtFmt),
                    l.getDescription(),
                    l.getCalories() != null ? l.getCalories() : 0));
        }
        return sb.toString().trim();
    }

    // ── Feature 4: Food log history ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public String getHistory(Long telegramId, int days) {
        User user = getUser(telegramId);
        StringBuilder sb = new StringBuilder();
        sb.append(isRU(user)
                ? String.format("История питания (последние %d дней):\n\n", days)
                : String.format("Food history (last %d days):\n\n", days));

        LocalDate today = LocalDate.now();
        for (int i = 0; i < days; i++) {
            LocalDate day = today.minusDays(i);
            LocalDateTime start = day.atStartOfDay();
            LocalDateTime end   = start.plusDays(1);
            List<FoodLog> logs  = foodLogRepository.findByUserIdAndLoggedAtBetween(user.getId(), start, end);

            if (logs.isEmpty()) continue;

            double cal  = logs.stream().mapToDouble(l -> l.getCalories() != null ? l.getCalories() : 0).sum();
            double prot = logs.stream().mapToDouble(l -> l.getProtein()  != null ? l.getProtein()  : 0).sum();
            double carb = logs.stream().mapToDouble(l -> l.getCarbs()    != null ? l.getCarbs()    : 0).sum();
            double fat  = logs.stream().mapToDouble(l -> l.getFat()      != null ? l.getFat()      : 0).sum();
            double fib  = logs.stream().mapToDouble(l -> l.getFiber()    != null ? l.getFiber()    : 0).sum();

            sb.append(String.format("📅 %s\n", day.format(DATE_FMT)));
            if (isRU(user)) {
                sb.append(String.format(
                        "  Калории: %.0f / %.0f ккал\n" +
                        "  Белки: %.0fg / %.0fg\n" +
                        "  Углеводы: %.0fg / %.0fg\n" +
                        "  Жиры: %.0fg / %.0fg\n" +
                        "  Клетчатка: %.0fg / %.0fg\n\n",
                        cal,  safeGoal(user.getCalorieGoal()),
                        prot, safeGoal(user.getProteinGoal()),
                        carb, safeGoal(user.getCarbGoal()),
                        fat,  safeGoal(user.getFatGoal()),
                        fib,  safeGoal(user.getFiberGoal())));
            } else {
                sb.append(String.format(
                        "  Calories: %.0f / %.0f kcal\n" +
                        "  Protein: %.0fg / %.0fg\n" +
                        "  Carbs: %.0fg / %.0fg\n" +
                        "  Fat: %.0fg / %.0fg\n" +
                        "  Fiber: %.0fg / %.0fg\n\n",
                        cal,  safeGoal(user.getCalorieGoal()),
                        prot, safeGoal(user.getProteinGoal()),
                        carb, safeGoal(user.getCarbGoal()),
                        fat,  safeGoal(user.getFatGoal()),
                        fib,  safeGoal(user.getFiberGoal())));
            }
        }

        String result = sb.toString().trim();
        return result.isEmpty()
                ? (isRU(user) ? "За этот период нет записей." : "No entries for this period.")
                : result;
    }

    // ── Meal suggestions ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public String getSuggestions(Long telegramId, String userMessage) {
        User user = getUser(telegramId);
        List<FoodLog> todayLogs = getTodayLogs(user.getId());
        double[] t = totals(todayLogs);

        double remainingCalories = safeGoal(user.getCalorieGoal()) - t[0];
        double remainingProtein  = safeGoal(user.getProteinGoal()) - t[1];
        double remainingCarbs    = safeGoal(user.getCarbGoal())    - t[2];
        double remainingFat      = safeGoal(user.getFatGoal())     - t[3];

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

    /** [0]=cal [1]=protein [2]=carbs [3]=fat [4]=fiber */
    private double[] totals(List<FoodLog> logs) {
        double cal  = logs.stream().mapToDouble(l -> l.getCalories() != null ? l.getCalories() : 0).sum();
        double prot = logs.stream().mapToDouble(l -> l.getProtein()  != null ? l.getProtein()  : 0).sum();
        double carb = logs.stream().mapToDouble(l -> l.getCarbs()    != null ? l.getCarbs()    : 0).sum();
        double fat  = logs.stream().mapToDouble(l -> l.getFat()      != null ? l.getFat()      : 0).sum();
        double fib  = logs.stream().mapToDouble(l -> l.getFiber()    != null ? l.getFiber()    : 0).sum();
        return new double[]{cal, prot, carb, fat, fib};
    }

    private String buildSummary(User user, NutritionResponse n, double[] t, boolean isEdit) {
        double cal  = t[0], prot = t[1], carb = t[2], fat = t[3], fib = t[4];

        String header = isEdit
                ? (isRU(user) ? "Запись обновлена" : "Entry updated")
                : (isRU(user) ? "Записано" : "Logged");

        double grams   = n.getGrams()    != null ? n.getGrams()    : 0;
        double nCal    = n.getCalories() != null ? n.getCalories() : 0;
        double nProt   = n.getProtein()  != null ? n.getProtein()  : 0;
        double nCarbs  = n.getCarbs()    != null ? n.getCarbs()    : 0;
        double nFat    = n.getFat()      != null ? n.getFat()      : 0;
        double nFiber  = n.getFiber()    != null ? n.getFiber()    : 0;
        String name    = n.getFoodName() != null ? n.getFoodName() : "Unknown";
        double waterGoal = safeGoal(user.getWaterGoal());

        if (isRU(user)) {
            return String.format("""
                    %s: %s (%.0fг)
                    Калории: %.0f ккал | Белки: %.0fг | Углеводы: %.0fг | Жиры: %.0fг | Клетчатка: %.0fг

                    Итого за сегодня:
                    Калории: %.0f / %.0f ккал
                    Белки: %.0fг / %.0fг
                    Углеводы: %.0fг / %.0fг
                    Жиры: %.0fг / %.0fг
                    Клетчатка: %.0fг / %.0fг
                    Цель по воде: %.0f мл/день
                    """,
                    header, name, grams,
                    nCal, nProt, nCarbs, nFat, nFiber,
                    cal,  safeGoal(user.getCalorieGoal()),
                    prot, safeGoal(user.getProteinGoal()),
                    carb, safeGoal(user.getCarbGoal()),
                    fat,  safeGoal(user.getFatGoal()),
                    fib,  safeGoal(user.getFiberGoal()),
                    waterGoal);
        } else {
            return String.format("""
                    %s: %s (%.0fg)
                    Calories: %.0f kcal | Protein: %.0fg | Carbs: %.0fg | Fat: %.0fg | Fiber: %.0fg

                    Today's total:
                    Calories: %.0f / %.0f kcal
                    Protein: %.0fg / %.0fg
                    Carbs: %.0fg / %.0fg
                    Fat: %.0fg / %.0fg
                    Fiber: %.0fg / %.0fg
                    Water goal: %.0f ml/day
                    """,
                    header, name, grams,
                    nCal, nProt, nCarbs, nFat, nFiber,
                    cal,  safeGoal(user.getCalorieGoal()),
                    prot, safeGoal(user.getProteinGoal()),
                    carb, safeGoal(user.getCarbGoal()),
                    fat,  safeGoal(user.getFatGoal()),
                    fib,  safeGoal(user.getFiberGoal()),
                    waterGoal);
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
