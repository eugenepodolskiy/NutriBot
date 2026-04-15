package com.nutribot.nutribot.services;

import com.nutribot.nutribot.dto.NutritionResponse;
import com.nutribot.nutribot.enums.LogSource;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds parsed nutrition results that are awaiting user confirmation.
 * Nothing is written to the database until the user replies yes/да.
 */
@Service
public class PendingConfirmationService {

    public static class PendingLog {
        private final NutritionResponse nutrition;
        private final LogSource logSource;
        private final String description;
        private Integer confirmationMessageId;

        public PendingLog(NutritionResponse nutrition, LogSource logSource, String description) {
            this.nutrition = nutrition;
            this.logSource = logSource;
            this.description = description;
        }

        public NutritionResponse getNutrition()              { return nutrition;              }
        public LogSource         getLogSource()              { return logSource;              }
        public String            getDescription()            { return description;            }
        public Integer           getConfirmationMessageId()  { return confirmationMessageId;  }
        public void              setConfirmationMessageId(Integer id) { this.confirmationMessageId = id; }
    }

    private final Map<Long, PendingLog> pending = new ConcurrentHashMap<>();

    public boolean hasPending(Long telegramId) {
        return pending.containsKey(telegramId);
    }

    public void store(Long telegramId, NutritionResponse nutrition, LogSource logSource, String description) {
        pending.put(telegramId, new PendingLog(nutrition, logSource, description));
    }

    public PendingLog getPending(Long telegramId) {
        return pending.get(telegramId);
    }

    public void clear(Long telegramId) {
        pending.remove(telegramId);
    }
}
