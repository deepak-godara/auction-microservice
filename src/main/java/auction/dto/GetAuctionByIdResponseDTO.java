package auction.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import auction.model.Status;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GetAuctionByIdResponseDTO {
    private Long          id;
    private String        sellerId;
    private String        title;
    private String        description;
    private BigDecimal    reservePrice;
    private BigDecimal    minBidIncrement;
    private BigDecimal    registrationFee;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer       snipeWindowMins;
    private Integer       maxExtensions;
    private Integer       extensionCount;
    private Status        status;
    private String        winnerId;
    private BigDecimal    auctionedPrice;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
