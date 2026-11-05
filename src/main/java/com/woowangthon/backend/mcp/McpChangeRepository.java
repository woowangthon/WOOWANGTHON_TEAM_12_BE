package com.woowangthon.backend.mcp;

import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpChangeRepository extends JpaRepository<McpChange, Long> {

    long countByUserIdAndTypeAndCreatedAtGreaterThanEqual(Long userId, McpChangeType type, OffsetDateTime from);
}
