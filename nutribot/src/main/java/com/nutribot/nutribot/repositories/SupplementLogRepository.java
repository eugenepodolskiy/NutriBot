package com.nutribot.nutribot.repositories;

import com.nutribot.nutribot.models.SupplementLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface SupplementLogRepository extends JpaRepository<SupplementLog, Long> {

    List<SupplementLog> findByUserIdAndTakenAtBetween(Long userId, LocalDateTime start, LocalDateTime end);

    boolean existsBySupplementIdAndUserIdAndTakenAtBetween(
            Long supplementId, Long userId, LocalDateTime start, LocalDateTime end);

    @Modifying
    @Query("DELETE FROM SupplementLog sl WHERE sl.supplement.id = :supplementId AND sl.user.id = :userId AND sl.takenAt BETWEEN :start AND :end")
    void deleteBySupplementIdAndUserIdAndTakenAtBetween(
            @Param("supplementId") Long supplementId,
            @Param("userId") Long userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    @Modifying
    @Query("DELETE FROM SupplementLog sl WHERE sl.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
