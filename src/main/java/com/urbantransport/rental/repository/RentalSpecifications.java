package com.urbantransport.rental.repository;

import com.urbantransport.rental.domain.RentalEntity;
import com.urbantransport.rental.domain.RentalStatus;
import jakarta.persistence.criteria.Predicate;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RentalSpecifications {

    public static Specification<RentalEntity> filter(RentalStatus status, UUID branchId, UUID customerUserId,
                                                     String customerNit, LocalDate departureFrom,
                                                     LocalDate departureTo) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (branchId != null) {
                predicates.add(cb.equal(root.get("branchId"), branchId));
            }
            if (customerUserId != null && customerNit != null) {
                predicates.add(cb.or(
                        cb.equal(root.get("customerUserId"), customerUserId),
                        cb.equal(root.get("customerNit"), customerNit)));
            }
            if (departureFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("departureDate"), departureFrom));
            }
            if (departureTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("departureDate"), departureTo));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
