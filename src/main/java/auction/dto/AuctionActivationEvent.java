package auction.dto;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import lombok.Builder;
import lombok.Data;
@Data
@Builder
public class AuctionActivationEvent {
    private Long Id;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private BigDecimal basePrice;
    private BigDecimal minBidIncrement;
    private List<String> registeredUsers;
}
    
