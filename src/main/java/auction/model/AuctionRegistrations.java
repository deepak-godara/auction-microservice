package auction.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import auction.model.types.RefundStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@Table(name = "auction_registrations")
@NoArgsConstructor
@AllArgsConstructor
public class AuctionRegistrations {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @Column(nullable = false)
    private String bidderId;           // userId from JWT — String, not FK

    @Column(nullable = false)
    private Boolean feePaid;           // false if free auction, true once fee confirmed

    private String feePaymentId;       // reference to payment-service record, null if free

    @Enumerated(EnumType.STRING)
    private RefundStatus refundStatus=RefundStatus.NOT_REQUIRED;

    private BigDecimal refundAmount = null;

    private String refundPaymentId  = null;

    @Column(nullable = false)
    private LocalDateTime registeredAt;
}