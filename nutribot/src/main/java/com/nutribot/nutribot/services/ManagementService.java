package com.nutribot.nutribot.services;

import com.nutribot.nutribot.models.FoodLog;
import com.nutribot.nutribot.models.Supplement;
import com.nutribot.nutribot.models.User;
import com.nutribot.nutribot.repositories.FoodLogRepository;
import com.nutribot.nutribot.repositories.SupplementRepository;
import com.nutribot.nutribot.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ManagementService {

    private final FoodLogRepository    foodLogRepository;
    private final SupplementRepository supplementRepository;
    private final UserRepository       userRepository;
    private final OnboardingService    onboardingService;
    private final ManagementStateService managementStateService;

    // ── 1. Delete last food log entry for today ────────────────────────────────

    @Transactional
    public String deleteLastFoodLog(Long telegramId) {
        User user = getUser(telegramId);
        String lang = user.getLanguage();
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay   = startOfDay.plusDays(1);

        Optional<FoodLog> last = foodLogRepository
                .findFirstByUserIdAndLoggedAtBetweenOrderByLoggedAtDesc(user.getId(), startOfDay, endOfDay);

        if (last.isEmpty()) {
            return isRU(lang)
                    ? "Сегодня ещё нет записей для удаления."
                    : "No food entries logged today to delete.";
        }

        FoodLog entry = last.get();
        String name = entry.getDescription() != null ? entry.getDescription() : "entry";
        foodLogRepository.delete(entry);

        return isRU(lang)
                ? String.format("Запись «%s» удалена.", name)
                : String.format("Entry \"%s\" deleted.", name);
    }

    // ── 2. Clear all of today's food log ──────────────────────────────────────

    @Transactional
    public String clearTodayFoodLog(Long telegramId) {
        User user = getUser(telegramId);
        String lang = user.getLanguage();
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay   = startOfDay.plusDays(1);

        int deleted = foodLogRepository.deleteByUserIdAndLoggedAtBetween(user.getId(), startOfDay, endOfDay);

        if (deleted == 0) {
            return isRU(lang)
                    ? "Сегодня нет записей для очистки."
                    : "No food entries for today to clear.";
        }

        return isRU(lang)
                ? String.format("Удалено записей за сегодня: %d.", deleted)
                : String.format("Cleared %d food log entr%s for today.", deleted, deleted == 1 ? "y" : "ies");
    }

    // ── 3. View supplements (formatted list) ──────────────────────────────────

    @Transactional(readOnly = true)
    public String viewSupplements(Long telegramId) {
        User user = getUser(telegramId);
        String lang = user.getLanguage();
        List<Supplement> supplements = supplementRepository.findByUserIdAndActiveTrue(user.getId());

        if (supplements.isEmpty()) {
            return isRU(lang)
                    ? "У вас пока нет добавленных добавок."
                    : "You have no supplements added yet.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(isRU(lang) ? "Ваши добавки:\n" : "Your supplements:\n");
        for (int i = 0; i < supplements.size(); i++) {
            Supplement s = supplements.get(i);
            String doseStr = (s.getDose() != null && s.getUnit() != null)
                    ? String.format(" — %.0f %s", s.getDose(), s.getUnit())
                    : (s.getDose() != null ? String.format(" — %.0f", s.getDose()) : "");
            String times = (s.getReminderTimes() != null && !s.getReminderTimes().isBlank())
                    ? (isRU(lang) ? " (напоминание: " : " (reminder: ") + s.getReminderTimes() + ")"
                    : "";
            sb.append(String.format("%d. %s%s%s\n", i + 1, s.getName(), doseStr, times));
        }
        return sb.toString().trim();
    }

    // ── 4. Start delete-supplement flow (show list + set state) ───────────────

    @Transactional(readOnly = true)
    public String startDeleteSupplement(Long telegramId) {
        User user = getUser(telegramId);
        String lang = user.getLanguage();
        List<Supplement> supplements = supplementRepository.findByUserIdAndActiveTrue(user.getId());

        if (supplements.isEmpty()) {
            return isRU(lang)
                    ? "У вас пока нет добавленных добавок."
                    : "You have no supplements to delete.";
        }

        managementStateService.setState(telegramId, ManagementStateService.AWAITING_SUPPLEMENT_DELETE);

        StringBuilder sb = new StringBuilder();
        sb.append(isRU(lang)
                ? "Ваши добавки. Напишите номер той, которую хотите удалить:\n"
                : "Your supplements. Reply with the number of the one to delete:\n");
        for (int i = 0; i < supplements.size(); i++) {
            Supplement s = supplements.get(i);
            String doseStr = (s.getDose() != null && s.getUnit() != null)
                    ? String.format(" — %.0f %s", s.getDose(), s.getUnit())
                    : (s.getDose() != null ? String.format(" — %.0f", s.getDose()) : "");
            sb.append(String.format("%d. %s%s\n", i + 1, s.getName(), doseStr));
        }
        return sb.toString().trim();
    }

    // ── 4b. Process the number reply ──────────────────────────────────────────

    @Transactional
    public String processDeleteSupplementByNumber(Long telegramId, String text) {
        managementStateService.clearState(telegramId);
        User user = getUser(telegramId);
        String lang = user.getLanguage();

        int number;
        try {
            number = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return isRU(lang)
                    ? "Пожалуйста, введите число из списка."
                    : "Please enter a number from the list.";
        }

        List<Supplement> supplements = supplementRepository.findByUserIdAndActiveTrue(user.getId());
        if (number < 1 || number > supplements.size()) {
            return isRU(lang)
                    ? String.format("Введите число от 1 до %d.", supplements.size())
                    : String.format("Please enter a number between 1 and %d.", supplements.size());
        }

        Supplement toDelete = supplements.get(number - 1);
        toDelete.setActive(false);
        supplementRepository.save(toDelete);

        return isRU(lang)
                ? "Добавка «" + toDelete.getName() + "» удалена."
                : "Supplement \"" + toDelete.getName() + "\" removed.";
    }

    // ── 5. Reset profile ──────────────────────────────────────────────────────

    @Transactional
    public String resetProfile(Long telegramId, String telegramLanguageCode) {
        Optional<User> userOpt = userRepository.findByTelegramId(telegramId);
        if (userOpt.isPresent()) {
            Long userId = userOpt.get().getId();
            foodLogRepository.deleteAllByUserId(userId);
            supplementRepository.deleteAllByUserId(userId);
            userRepository.delete(userOpt.get());
            userRepository.flush();
        }
        // Restart onboarding — detects no user in DB and begins fresh
        return onboardingService.start(telegramId, telegramLanguageCode);
    }

    // ── 6. View current goals ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public String viewGoals(Long telegramId) {
        User user = getUser(telegramId);
        String lang = user.getLanguage();

        double cal   = user.getCalorieGoal()  != null ? user.getCalorieGoal()  : 0;
        double prot  = user.getProteinGoal()  != null ? user.getProteinGoal()  : 0;
        double carb  = user.getCarbGoal()     != null ? user.getCarbGoal()     : 0;
        double fat   = user.getFatGoal()      != null ? user.getFatGoal()      : 0;
        double fiber = user.getFiberGoal()    != null ? user.getFiberGoal()    : 0;
        double water = user.getWaterGoal()    != null ? user.getWaterGoal()    : 0;

        if (isRU(lang)) {
            return String.format(
                    "Ваши текущие ежедневные цели:\n\n" +
                    "Калории:   %.0f ккал\n" +
                    "Белки:     %.0f г\n" +
                    "Углеводы:  %.0f г\n" +
                    "Жиры:      %.0f г\n" +
                    "Клетчатка: %.0f г\n" +
                    "Вода:      %.0f мл",
                    cal, prot, carb, fat, fiber, water);
        } else {
            return String.format(
                    "Your current daily goals:\n\n" +
                    "Calories: %.0f kcal\n" +
                    "Protein:  %.0f g\n" +
                    "Carbs:    %.0f g\n" +
                    "Fat:      %.0f g\n" +
                    "Fiber:    %.0f g\n" +
                    "Water:    %.0f ml",
                    cal, prot, carb, fat, fiber, water);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private User getUser(Long telegramId) {
        return userRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new RuntimeException("User not found. Send /start to register."));
    }

    private static boolean isRU(String lang) {
        return "RU".equals(lang);
    }
}
