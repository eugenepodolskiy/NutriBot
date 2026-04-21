package com.nutribot.nutribot.services;

import com.nutribot.nutribot.bot.TelegramMessageSender;
import com.nutribot.nutribot.models.FoodLog;
import com.nutribot.nutribot.models.Supplement;
import com.nutribot.nutribot.models.User;
import com.nutribot.nutribot.repositories.FoodLogRepository;
import com.nutribot.nutribot.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderService {

    private final UserRepository        userRepository;
    private final FoodLogRepository     foodLogRepository;
    private final TelegramMessageSender messageSender;
    private final SupplementService     supplementService;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // ── Daily nutrition reminders — runs every minute, fires at HH:00 for each user's timezone

    @Scheduled(cron = "0 * * * * *")
    public void sendReminders() {
        for (User user : userRepository.findAll()) {
            ZoneId zone = SupplementService.getZone(user.getTimezone());
            ZonedDateTime userNow = ZonedDateTime.now(zone);
            log.info("REMINDER_CHECK: userId={} dbTimezone='{}' resolvedZone='{}' userLocalTime='{}'",
                    user.getId(), user.getTimezone(), zone.getId(),
                    userNow.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")));
            if (userNow.getMinute() != 0) continue;
            int hour = userNow.getHour();
            if (hour != 8 && hour != 12 && hour != 16 && hour != 20) continue;

            // Query today's logs using user's timezone boundaries converted to UTC
            LocalDateTime startUtc = userNow.toLocalDate().atStartOfDay(zone)
                    .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
            LocalDateTime endUtc = startUtc.plusDays(1);

            List<FoodLog> todayLogs = foodLogRepository
                    .findByUserIdAndLoggedAtBetween(user.getId(), startUtc, endUtc);

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

    // ── Supplement reminders — checked every minute using per-user timezone ────

    @Scheduled(cron = "0 * * * * *")
    public void checkSupplementReminders() {
        List<Supplement> allActive = supplementService.findAllActiveSupplements();

        for (Supplement s : allActive) {
            if (s.getReminderTimes() == null || s.getReminderTimes().isBlank()) continue;

            User user = s.getUser();
            ZoneId zone = SupplementService.getZone(user.getTimezone());
            String userTime = LocalTime.now(zone).format(TIME_FMT);

            if (!containsTime(s.getReminderTimes(), userTime)) continue;

            String lang = user.getLanguage();
            String doseStr = (s.getDose() != null)
                    ? String.format(" %.0f%s", s.getDose(),
                            s.getUnit() != null && !s.getUnit().isBlank() ? " " + s.getUnit() : "")
                    : "";

            String text = isRU(lang)
                    ? String.format("Время принять %s%s!", s.getName(), doseStr)
                    : String.format("Time to take %s%s!", s.getName(), doseStr);

            messageSender.send(buildSupplementReminderMessage(user.getTelegramId(), s.getId(), text, lang));
        }
    }

    // ── Supplement reminder message with Yes/No inline buttons ────────────────

    private static SendMessage buildSupplementReminderMessage(Long chatId, Long supplementId, String text, String lang) {
        boolean ru = "RU".equals(lang);

        InlineKeyboardButton takenBtn = InlineKeyboardButton.builder()
                .text(ru ? "✅ Принял(а)" : "✅ Yes, taken")
                .callbackData("supp_taken:" + supplementId)
                .build();

        InlineKeyboardButton skipBtn = InlineKeyboardButton.builder()
                .text(ru ? "⏭ Пропустить" : "⏭ Skip")
                .callbackData("supp_skip:" + supplementId)
                .build();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(takenBtn, skipBtn))
                .build();

        return SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(keyboard)
                .build();
    }

    private static boolean containsTime(String reminderTimes, String time) {
        for (String t : reminderTimes.split(",")) {
            if (t.trim().equals(time)) return true;
        }
        return false;
    }

    private static boolean isRU(User user) {
        return "RU".equals(user.getLanguage());
    }

    private static boolean isRU(String lang) {
        return "RU".equals(lang);
    }

    private static double safeGoal(Double goal) {
        return goal != null ? goal : 0;
    }
}
