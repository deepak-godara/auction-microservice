package auction.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateAuctionRequestDTO {

    @NotBlank(message = "Title is required")
    private String title;

    // optional — no validation annotation
    private String description;

    @NotNull(message = "Reserve price is required")
    @DecimalMin(value = "0.01", message = "Reserve price must be greater than 0")
    private BigDecimal reservePrice;

    @NotNull(message = "Minimum bid increment is required")
    @DecimalMin(value = "0.01", message = "Minimum bid increment must be greater than 0")
    private BigDecimal minBidIncrement;

    @NotNull(message = "Registration fee is required")
    @DecimalMin(value = "0.00", message = "Registration fee cannot be negative")
    private BigDecimal registrationFee;   // null means free — service treats null as 0.00

    @NotNull(message = "Start time is required")
    @Future(message = "Start time must be in the future")
    private LocalDateTime startTime;

    @NotNull(message = "Duration is required")
    @Min(value = 1, message = "Duration must be at least 1 minute")
    private Integer durationMins;

    @NotNull(message = "Snipe window is required")
    @Min(value = 1, message = "Snipe window must be at least 1 minute")
    private Integer snipeWindowMins;

    @NotNull(message = "Max extensions is required")
    @Min(value = 0, message = "Max extensions cannot be negative")
    private Integer maxExtensions;
}
