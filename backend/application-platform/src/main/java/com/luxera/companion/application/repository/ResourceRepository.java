package com.luxera.companion.application.repository;

import com.luxera.companion.application.domain.ResourceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ResourceRepository extends JpaRepository<ResourceRecord, String> {

    Optional<ResourceRecord> findByUri(String uri);

    List<ResourceRecord> findBySessionId(String sessionId);

    List<ResourceRecord> findByApplicationId(String applicationId);

    /**
     * 乐观并发的唯一闸门。<b>绝不无条件 UPDATE。</b>
     *
     * <p>返回 0 表示"我读到的版本已经不是当前版本了" —— 调用方必须把它翻成
     * {@code STATE_CONFLICT} 并把当前版本带回去, 让对家能立即重读重试,
     * 而不是拿到一个失败就放弃(那会让"两个 principal 抢同一步棋"变成随机丢子)。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ResourceRecord r set r.stateJson = :state, r.stateVersion = r.stateVersion + 1, "
            + "r.updatedAt = :now where r.uri = :uri and r.stateVersion = :expected")
    int compareAndSet(@Param("uri") String uri,
                      @Param("state") String state,
                      @Param("expected") long expected,
                      @Param("now") LocalDateTime now);
}
