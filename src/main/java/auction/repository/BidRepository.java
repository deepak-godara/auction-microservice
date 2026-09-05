package auction.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import auction.model.Bid;

@Repository
public interface BidRepository extends JpaRepository<Bid, Long> {
}
