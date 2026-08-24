package auction.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import auction.model.AuctionRegistrations;
// import jakarta.transaction.Transactional;

import java.util.Optional;
import java.math.BigDecimal;
import java.util.List;
import auction.model.types.RefundStatus;



public interface AuctionRegistrationsRepository extends JpaRepository<AuctionRegistrations, Long>{

    Optional<AuctionRegistrations>  findByAuction_IdAndBidderId(Long auctionId, String bidderId);
    Page<AuctionRegistrations> findByAuction_Id(Long auction_Id,Pageable pageable);
    Page<AuctionRegistrations> findByBidderId(String bidderId,Pageable pageable);

    List<AuctionRegistrations> findAllByAuction_IdAndFeePaidTrue(Long auctionId);
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

    
            @Modifying
            @Transactional
            @Query(" UPDATE AuctionRegistrations r "+
                    " SET r.refundStatus= :refundStatus," +
                    " r.refundPaymentId= :paymentId" + 
                    " WHERE r.auction.id = :auctionId" +
                    " AND r.bidderId= :bidderId"+
                    "  AND r.refundStatus = auction.model.types.RefundStatus.PENDING"
             )
             int  markRefundDoneforUser(@Param("auctionId") Long auctionId,
            @Param("bidderId") String bidderId,
            @Param("paymentId") String paymentId,
        @Param ("refundStatus") RefundStatus refundStatus);
    
    long countByAuction_IdAndFeePaidTrueAndRefundStatus(Long auctionId, RefundStatus refundStatus);
} 