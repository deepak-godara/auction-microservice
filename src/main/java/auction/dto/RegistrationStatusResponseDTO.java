package auction.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RegistrationStatusResponseDTO {
    private Long    registrationId;
    private Long    auctionId;
    private String  bidderId;
    private Boolean registered;
    private Boolean feePaid;
    private String  feePaymentId;
    private LocalDateTime registeredAt;
}
