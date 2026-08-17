package auction.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import auction.model.types.RefundStatus;
import auction.model.types.Status;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@Entity
@RequiredArgsConstructor
@AllArgsConstructor
public class Auction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)

    private String  sellerId;
    @Column(nullable = false)
    private String  title;

    @Column
    private String  description;
    
    @Column(nullable = false)
    private BigDecimal    reservePrice;
    @Column(nullable = false)

    private BigDecimal    minBidIncrement;
    
    @Column(nullable = false)
    private BigDecimal    registrationFee;

    @Column(nullable = false)
    private LocalDateTime    startTime;

    @Column(nullable = false)
    private LocalDateTime    endTime;

    @Column(nullable = false)
    private Integer snipeWindowMins;

    @Column(nullable = false)
    private Integer maxExtensions;

    @Column(nullable = false)
    private Integer extensionCount;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING )
    private Status  status;

    private String    winnerId;

    private BigDecimal    auctionedPrice;

    @Column(nullable = false)
    private LocalDateTime    createdAt;

    private LocalDateTime   updatedAt;
}
