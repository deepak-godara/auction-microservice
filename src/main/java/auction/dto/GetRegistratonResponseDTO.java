package auction.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GetRegistratonResponseDTO {
    private Long registrationId;
    private Long auctionId;
    private Boolean feePaid;
    private String feePaymentId;
    private LocalDateTime registeredAt;
}
