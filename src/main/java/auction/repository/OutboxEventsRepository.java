package auction.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import auction.model.OutboxEvents;

@Repository
public interface OutboxEventsRepository extends JpaRepository<OutboxEvents, Long> {

    @Query(value = "SELECT * FROM auction_outbox_events WHERE status = 'PENDING' " +
                   "ORDER BY auction_id ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<OutboxEvents> findPendingEventsForUpdateSkipLocked(@Param("limit") int limit);

    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvents o SET o.status = auction.model.types.OutboxEventStatus.PENDING " +
           "WHERE o.status = auction.model.types.OutboxEventStatus.PROCESSING " +
           "AND o.lastRetryAt < :cutoff")
    int resetStuckProcessingEvents(@Param("cutoff") LocalDateTime cutoff);
}
