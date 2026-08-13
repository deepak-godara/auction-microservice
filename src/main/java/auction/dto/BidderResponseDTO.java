package auction.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BidderResponseDTO {

    private Long registrationId;
    private String bidderId;
    private Boolean feePaid;
    private String feePaymentId;
    private LocalDateTime registeredAt;
}
