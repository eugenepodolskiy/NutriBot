package com.nutribot.nutribot.services;

import com.nutribot.nutribot.bot.TelegramMessageSender;
import com.nutribot.nutribot.models.FoodLog;
import com.nutribot.nutribot.models.User;
import com.nutribot.nutribot.repositories.FoodLogRepository;
import com.nutribot.nutribot.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReminderService {

    private final UserRepository userRepository;
    private final FoodLogRepository foodLogRepository;
    private final TelegramMessageSender messageSender;

    /**
     * Sends a personalized reminder to every registered user at 8:00, 12:00,
     * 16:00, and 20:00 every day showing their remaining calories and protein.
     */
    @Scheduled(cron = "0 0 8,12,16,20 * * *")
    public void sendReminders() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay   = startOfDay.plusDays(1);

        for (User user : userRepository.findAll()) {
            List<FoodLog> todayLogs = foodLogRepository
                    .findByUserIdAndLoggedAtBetween(user.getId(), startOfDay, endOfDay);

            double loggedCalories = todayLogs.stream().mapToDouble(FoodLog::getCalories).sum();
            double loggedProtein  = todayLogs.stream().mapToDouble(FoodLog::getProtein).sum();

            double remainingCalories = safeGoal(user.getCalorieGoal()) - loggedCalories;
            double remainingProtein  = safeGoal(user.getProteinGoal()) - loggedProtein;

            String message = isRU(user)
                    ? String.format("Привет, %s! Не забудь поесть. Осталось: %.0f ккал, %.0fг белка.",
                            user.getName(), remainingCalories, remainingProtein)
                    : String.format("Hey %s! Time to eat. Remaining: %.0f kcal, %.0fg protein.",
                            user.getName(), remainingCalories, remainingProtein);

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
