package auction.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GetAuctionsResponseDTO {
    private Long          id;
    private String        title;
    private String        description;      // full or truncated
    private BigDecimal    reservePrice;
    private BigDecimal    registrationFee;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}