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

    List<Name> findNamesByIdIn(Collection<Long> ids);

    Optional<Source> findByIdAndUserId(Long id, Long userId);
}
