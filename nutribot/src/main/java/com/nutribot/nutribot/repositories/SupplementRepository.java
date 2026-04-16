package com.nutribot.nutribot.repositories;

import com.nutribot.nutribot.models.Supplement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SupplementRepository extends JpaRepository<Supplement, Long> {

    List<Supplement> findByUserIdAndActiveTrue(Long userId);

    List<Supplement> findByUserId(Long userId);

    Optional<Supplement> findFirstByUserIdAndNameIgnoreCaseAndActiveTrue(Long userId, String name);

    /** Find all active supplements whose reminder_times contain the given HH:mm token. */
    @Query("SELECT s FROM Supplement s WHERE s.active = true AND s.reminderTimes LIKE %:time%")
    List<Supplement> findActiveByReminderTime(@Param("time") String time);

    /** Bulk delete all supplements for a user — used by profile reset. */
    @Modifying
    @Query("DELETE FROM Supplement s WHERE s.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
