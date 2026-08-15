package auction.repository;

import org.springframework.stereotype.Repository;

import auction.model.Auction;
import auction.model.types.Status;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

@Repository
public interface  AuctionRepository extends JpaRepository<Auction, Long>{
    
    Optional<Auction> findBySellerIdAndTitleAndStatusIn(String sellerId, String title, List<Status> statuses);
    Page<Auction> findByStatus(Status status, Pageable pageable);
    Page<Auction> findBySellerId(String sellerId, Pageable pageable);

    @Query(value ="SELECT * FROM auction WHERE status = 'CREATED' AND refund_status = 'NOT_REQUIRED' AND end_time <= NOW() ORDER BY id ASC LIMIT 2 FOR UPDATE SKIP LOCKED",nativeQuery = true)
    List<Auction> findEndedAuctionsForUpdateSkipLocked();
}
