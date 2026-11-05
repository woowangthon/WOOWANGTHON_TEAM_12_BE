package com.woowangthon.backend.account;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByLoginId(String loginId);

    boolean existsByLoginId(String loginId);

    // 사용자별 개수 한도(지침 켠 것 · 전체)를 세고 넣는 사이에 다른 요청이 끼지 않게 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> lockById(Long id);

    // 다른 기기 · 늦게 도착한 요청이 겹쳐도 뒤로 가지 않게 조건을 UPDATE 안에 둔다
    @Modifying(clearAutomatically = true)
    @Query("update AppUser u set u.workSeenAt = :seenAt where u.id = :id and (u.workSeenAt is null or u.workSeenAt < :seenAt)")
    void advanceWorkSeenAt(Long id, OffsetDateTime seenAt);
}
