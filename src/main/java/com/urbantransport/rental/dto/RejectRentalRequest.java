package com.urbantransport.rental.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectRentalRequest(
        @NotBlank @Size(max = 255) String reason) {
}
