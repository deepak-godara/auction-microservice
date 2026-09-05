package auction.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import auction.dto.BId;
import auction.exception.AuctionNotActiveException;
import auction.exception.BidTooLowException;
import auction.exception.BidderNotRegisteredException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BidService {

    private final RedisTemplate<String, String> redisTemplate;
    private static final RedisScript<String> bidsubmitionScript = RedisScript.of("""
            local bid = redis.call(
            'XADD',
            KEYS[1],
            '*',
            'auctionId', ARGV[1],
            'bidderId', ARGV[2],
            'amount', ARGV[3]
            )
            redis.call(
            'INCR',
            KEYS[2]
            )
            return bid
            """, String.class);

    public String writeBidToStream(Long auctionId, String bidderId, BId bid) {

        // 1. Check bidder is registered for this auction
        // key written by WorkerRegistry — "auction:{id}:users"
        String registeredKey = "auction:{" + auctionId + "}:users";
        Boolean isRegistered = redisTemplate.opsForSet().isMember(registeredKey, bidderId);
        if (!Boolean.TRUE.equals(isRegistered)) {
            throw new BidderNotRegisteredException(auctionId);
        }

        // 2. Fetch auction meta — startTime, endTime, basePrice, minBidIncrement
        String metaKey = "auction:{" + auctionId + "}:metadata";
        List<Object> metaData = redisTemplate.opsForHash().multiGet(
                metaKey, List.of("startTime", "endTime", "basePrice", "minBidIncrement"));

        // Null check covers all fields — any missing field means metadata was never written
        if (metaData.get(0) == null || metaData.get(1) == null
                || metaData.get(2) == null || metaData.get(3) == null) {
            throw new AuctionNotActiveException(auctionId);
        }

        LocalDateTime startTime      = LocalDateTime.parse((String) metaData.get(0));
        LocalDateTime endTime        = LocalDateTime.parse((String) metaData.get(1));
        BigDecimal    basePrice      = new BigDecimal((String) metaData.get(2));
        BigDecimal    minBidIncrement = new BigDecimal((String) metaData.get(3));

        // 3. Check auction is currently live
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(startTime) || now.isAfter(endTime)) {
            throw new AuctionNotActiveException(auctionId);
        }

        // 4. Check bid meets basePrice + minBidIncrement floor
        BigDecimal minimumBid = basePrice.add(minBidIncrement);
        if (bid.getBidAmount().compareTo(minimumBid) < 0) {
            throw new BidTooLowException(auctionId, minimumBid);
        }

        // 5. Write to Redis Stream atomically with INCR of per-auction pending counter.
        // KEYS[1] = stream, KEYS[2] = pel counter key
        String messageId = redisTemplate.execute(
                bidsubmitionScript,
                List.of("bids:tobeProcessed", "auction:{" + auctionId + "}:pel"),
                String.valueOf(auctionId), bidderId, bid.getBidAmount().toPlainString());
        // RecordId recordId = redisTemplate.opsForStream().add(
        //         MapRecord.create("bids:tobeProcessed", bidData));

        return messageId;
    }
}
