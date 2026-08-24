package auction.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import auction.model.Auction;
import auction.model.types.Status;

@Repository
public interface AuctionRepository extends JpaRepository<Auction, Long> {

    Optional<Auction> findBySellerIdAndTitleAndStatusIn(String sellerId, String title, List<Status> statuses);
    Page<Auction> findByStatus(Status status, Pageable pageable);
    Page<Auction> findBySellerId(String sellerId, Pageable pageable);

    // SKIP LOCKED — safe multi-pod operation, processes auctions with status CLOSED
    @Query(value = "SELECT * FROM auction WHERE status = 'CLOSED' AND end_time <= NOW() " +
                   "ORDER BY id ASC LIMIT 5 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Auction> findEndedAuctionsForUpdateSkipLocked();

    List<Auction> findByStatusAndStartTimeBetweenOrderByStartTimeAsc(Status status,LocalDateTime from,
        LocalDateTime to);
    @Modifying
    @Transactional
    @Query("UPDATE Auction a SET a.status = :status WHERE a.id = :auctionId")
    int updateAuctionStatus(
            @Param("auctionId") Long auctionId,
            @Param("status") Status status);
}
