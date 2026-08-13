package auction.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor

public class CreateAuctionResponseDTO {
    private Long auctionId;
    private String message;
}
