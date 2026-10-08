package auction.client.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentStatusResponseDTO {

    // SUCCESS | CREATED | EXPIRED | NOT_FOUND
    private String        status;
    private LocalDateTime orderExpiresAt;
}
