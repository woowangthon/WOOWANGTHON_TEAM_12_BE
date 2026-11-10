package com.woowangthon.backend.source;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceRepository extends JpaRepository<Source, Long> {

    // 원문을 읽지 않고 이름만
    interface Name {
        Long getId();

        String getName();
    }

    // 같은 자료 알아보기(S-69) — 원문을 읽지 않고 id · 이름 · 넣은 때만
    interface Found {
        Long getId();

        String getName();

        java.time.OffsetDateTime getCreatedAt();
    }

    // 같은 해시가 여럿이면 가장 최근에 넣은 것
    Optional<Found> findFirstByUserIdAndContentHashOrderByCreatedAtDescIdDesc(Long userId, String contentHash);

    List<Name> findNamesByIdIn(Collection<Long> ids);

    Optional<Source> findByIdAndUserId(Long id, Long userId);
}
