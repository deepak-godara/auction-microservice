package auction.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WinnerStatusResponseDTO {
    private Boolean    isWinner;
    private BigDecimal auctionedPrice;
    private Boolean    alreadyPaid;    // true if auction status is COMPLETED (winner already paid)
}
