package auction.repository;

import org.antlr.v4.runtime.atn.SemanticContext.AND;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import auction.model.AuctionRegistrations;
import jakarta.transaction.Transactional;

import java.util.Optional;
import java.math.BigDecimal;
import java.util.List;



public interface AuctionRegistrationsRepository extends JpaRepository<AuctionRegistrations, Long>{

    Optional<AuctionRegistrations>  findByAuction_IdAndBidderId(Long auctionId, String bidderId);
    Page<AuctionRegistrations> findByAuction_Id(Long auction_Id,Pageable pageable);
    Page<AuctionRegistrations> findByBidderId(String bidderId,Pageable pageable);

    @Modifying
    @Transactional
    @Query("UPDATE AuctionRegistrations r SET r.feePaid = true, r.feePaymentId = :paymentId WHERE r.auction.id = :auctionId AND r.bidderId = :bidderId AND r.feePaid = false")
    int updateFeePaid(
            @Param("auctionId") Long auctionId,
            @Param("bidderId") String bidderId,
            @Param("paymentId") String paymentId);

        @Modifying
    @Transactional
    @Query("UPDATE AuctionRegistrations r " +
           "SET r.refundStatus = auction.model.types.RefundStatus.PENDING, " +
           "    r.refundAmount = :refundAmount " +
           "WHERE r.auction.id = :auctionId " +
           "  AND r.feePaid = true " +
           "  AND r.bidderId NOT IN (:excludedBidderIds)")
    int markEligibleLosersAsPending(
            @Param("auctionId") Long auctionId,
            @Param("refundAmount") BigDecimal refundAmount,
            @Param("excludedBidderIds") List<String> excludedBidderIds);

    
} 