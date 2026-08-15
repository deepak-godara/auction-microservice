package auction.dto;

import java.util.List;

import org.aspectj.lang.annotation.RequiredTypes;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
@AllArgsConstructor
@Builder
public class AuctionEndedRequestDTO {

    private Long auctionId;

    private List<String> excludedBidderIds;
    
}
