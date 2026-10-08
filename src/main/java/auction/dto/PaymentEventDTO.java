package auction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@Builder
@NoArgsConstructor
public class PaymentEventDTO {

    private Long auctionId;
    private String userId;
    private String transactionId; 
    
}