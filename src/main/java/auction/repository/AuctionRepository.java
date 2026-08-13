package auction.repository;

import org.springframework.stereotype.Repository;

import auction.model.Auction;
import auction.model.Status;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

@Repository
public interface  AuctionRepository extends JpaRepository<Auction, Long>{
    
    Optional<Auction> findBySellerIdAndTitleAndStatusIn(String sellerId, String title, List<Status> statuses);
    Page<Auction> findByStatus(Status status, Pageable pageable);
    Page<Auction> findBySellerId(String sellerId, Pageable pageable);
    
}
