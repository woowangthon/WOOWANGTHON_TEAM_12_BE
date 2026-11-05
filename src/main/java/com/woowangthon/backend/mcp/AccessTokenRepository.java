package com.woowangthon.backend.mcp;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AccessTokenRepository extends JpaRepository<AccessToken, Long> {

    Optional<AccessToken> findByTokenHash(String tokenHash);

    Optional<AccessToken> findFirstByUserIdAndScopeOrderByIdDesc(Long userId, String scope);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AccessToken t where t.userId = :userId and t.scope = :scope")
    void deleteOfUser(Long userId, String scope);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AccessToken t where t.scope = :scope")
    int deleteAllOfScope(String scope);
}
