package auction.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import auction.model.AuctionRegistrations;
import java.util.Optional;
import java.util.List;



public interface AuctionRegistrationsRepository extends JpaRepository<AuctionRegistrations, Long>{

    Optional<AuctionRegistrations>  findByAuction_IdAndBidderId(Long auctionId, String bidderId);
    Page<AuctionRegistrations> findByAuction_Id(Long auction_Id,Pageable pageable);
    Page<AuctionRegistrations> findByBidderId(String bidderId,Pageable pageable);
} 