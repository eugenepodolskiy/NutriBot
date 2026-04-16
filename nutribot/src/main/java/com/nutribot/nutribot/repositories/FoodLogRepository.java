package com.nutribot.nutribot.repositories;

import com.nutribot.nutribot.models.FoodLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FoodLogRepository extends JpaRepository<FoodLog, Long> {

    List<FoodLog> findByUserIdAndLoggedAtBetween(Long userId, LocalDateTime start, LocalDateTime end);

    /** Most recent log in a time window — used by editLastLog. */
    Optional<FoodLog> findFirstByUserIdAndLoggedAtBetweenOrderByLoggedAtDesc(
            Long userId, LocalDateTime start, LocalDateTime end);

    /** Last 5 entries across all time — used by getRecentLogs. */
    List<FoodLog> findTop5ByUserIdOrderByLoggedAtDesc(Long userId);
}
