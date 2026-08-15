package auction.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import auction.model.AuctionTopBidders;

public interface AuctionTopBiddersRepository extends JpaRepository<AuctionTopBidders,Long>{
    
    List<AuctionTopBidders> findAllByAuctionId(Long auctionId);
}
