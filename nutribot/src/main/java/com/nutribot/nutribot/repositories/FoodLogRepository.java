package com.nutribot.nutribot.repositories;

import com.nutribot.nutribot.models.FoodLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface FoodLogRepository extends JpaRepository<FoodLog, Long> {
    List<FoodLog> findByUserIdAndLoggedAtBetween(Long userId,
                                                 LocalDateTime start, LocalDateTime end);
}
