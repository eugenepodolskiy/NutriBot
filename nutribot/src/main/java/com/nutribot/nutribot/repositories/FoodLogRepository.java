package com.nutribot.nutribot.repositories;

import com.nutribot.nutribot.models.FoodLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** Last 20 entries in a time window ordered by most recent — used by getRecentDistinctDishes. */
    List<FoodLog> findTop20ByUserIdAndLoggedAtBetweenOrderByLoggedAtDesc(
            Long userId, LocalDateTime start, LocalDateTime end);

    /** All logs for a user — used by profile reset. */
    List<FoodLog> findByUserId(Long userId);

    /** Bulk delete all logs in a time window — used by clear-today. Returns deleted count. */
    @Modifying
    @Query("DELETE FROM FoodLog f WHERE f.user.id = :userId AND f.loggedAt BETWEEN :start AND :end")
    int deleteByUserIdAndLoggedAtBetween(@Param("userId") Long userId,
                                         @Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end);

    /** Bulk delete all logs for a user — used by profile reset. */
    @Modifying
    @Query("DELETE FROM FoodLog f WHERE f.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
