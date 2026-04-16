package com.nutribot.nutribot.services;

import com.nutribot.nutribot.bot.TelegramMessageSender;
import com.nutribot.nutribot.models.FoodLog;
import com.nutribot.nutribot.models.Supplement;
import com.nutribot.nutribot.models.User;
import com.nutribot.nutribot.repositories.FoodLogRepository;
import com.nutribot.nutribot.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReminderService {

    private final UserRepository       userRepository;
    private final FoodLogRepository    foodLogRepository;
    private final TelegramMessageSender messageSender;
    private final SupplementService    supplementService;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // ── Daily nutrition reminders at 8 / 12 / 16 / 20 ────────────────────────

    @Scheduled(cron = "0 0 8,12,16,20 * * *")
    public void sendReminders() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay   = startOfDay.plusDays(1);

        for (User user : userRepository.findAll()) {
            List<FoodLog> todayLogs = foodLogRepository
                    .findByUserIdAndLoggedAtBetween(user.getId(), startOfDay, endOfDay);

            double loggedCalories = todayLogs.stream().mapToDouble(l -> l.getCalories() != null ? l.getCalories() : 0).sum();
            double loggedProtein  = todayLogs.stream().mapToDouble(l -> l.getProtein()  != null ? l.getProtein()  : 0).sum();
            double loggedFiber    = todayLogs.stream().mapToDouble(l -> l.getFiber()    != null ? l.getFiber()    : 0).sum();

            double remainingCalories = safeGoal(user.getCalorieGoal()) - loggedCalories;
            double remainingProtein  = safeGoal(user.getProteinGoal()) - loggedProtein;
            double remainingFiber    = safeGoal(user.getFiberGoal())   - loggedFiber;
            double waterGoal         = safeGoal(user.getWaterGoal());

            String message = isRU(user)
                    ? String.format(
                            "Привет, %s! Не забудь поесть.\n" +
                            "Осталось: %.0f ккал, %.0fг белка, %.0fг клетчатки.\n" +
                            "Цель по воде: %.0f мл/день.",
                            user.getName(), remainingCalories, remainingProtein, remainingFiber, waterGoal)
                    : String.format(
                            "Hey %s! Time to eat.\n" +
                            "Remaining: %.0f kcal, %.0fg protein, %.0fg fiber.\n" +
                            "Water goal: %.0f ml/day.",
                            user.getName(), remainingCalories, remainingProtein, remainingFiber, waterGoal);

            messageSender.sendMessage(user.getTelegramId(), message);
        }
    }

    // ── Supplement reminders — checked every minute ───────────────────────────

    @Scheduled(cron = "0 * * * * *")
    public void checkSupplementReminders() {
        String currentTime = LocalTime.now().format(TIME_FMT);
        List<Supplement> due = supplementService.findDueSupplements(currentTime);

        for (Supplement s : due) {
            User user = s.getUser();
            String lang = user.getLanguage();

            String doseStr = (s.getDose() != null)
                    ? String.format(" %.0f%s", s.getDose(),
                            s.getUnit() != null && !s.getUnit().isBlank() ? " " + s.getUnit() : "")
                    : "";

            String message = "RU".equals(lang)
                    ? String.format("Время принять %s%s!", s.getName(), doseStr)
                    : String.format("Time to take %s%s!", s.getName(), doseStr);

            messageSender.sendMessage(user.getTelegramId(), message);
        }
    }

    private static boolean isRU(User user) {
        return "RU".equals(user.getLanguage());
    }

    private static double safeGoal(Double goal) {
        return goal != null ? goal : 0;
    }
}
