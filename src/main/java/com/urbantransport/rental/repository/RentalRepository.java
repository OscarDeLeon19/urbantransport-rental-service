package com.urbantransport.rental.repository;

import com.urbantransport.rental.domain.RentalEntity;
import com.urbantransport.rental.domain.RentalStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RentalRepository extends JpaRepository<RentalEntity, UUID>, JpaSpecificationExecutor<RentalEntity> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RentalEntity r where r.id = :id")
    Optional<RentalEntity> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RentalEntity r where r.status = :status and r.paymentDate is not null"
            + " and (r.paymentRequestedAt is null or r.paymentRequestedAt < :requestedBefore)")
    List<RentalEntity> findStalePaymentsForUpdate(@Param("status") RentalStatus status,
                                                  @Param("requestedBefore") Instant requestedBefore);
}
