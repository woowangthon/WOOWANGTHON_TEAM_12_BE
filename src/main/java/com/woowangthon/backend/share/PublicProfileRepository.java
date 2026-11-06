package com.woowangthon.backend.share;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PublicProfileRepository extends JpaRepository<PublicProfile, Long> {

    Optional<PublicProfile> findByToken(String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PublicProfile p where p.userId = :userId")
    Optional<PublicProfile> findForUpdate(Long userId);

    // 첫 요청이 동시에 와도 줄이 하나만 생기게
    @Modifying
    @Query(value = "insert into public_profile (user_id) values (:userId) on conflict do nothing", nativeQuery = true)
    void insertIfAbsent(Long userId);
}
