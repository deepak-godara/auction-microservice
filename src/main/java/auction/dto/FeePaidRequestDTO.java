package auction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@Builder
@NoArgsConstructor
public class FeePaidRequestDTO {

    private Long auctionId;
    private String bidderId;
    private String paymentId; 
    
}