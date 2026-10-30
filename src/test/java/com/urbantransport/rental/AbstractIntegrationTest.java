package com.urbantransport.rental;

import com.jayway.jsonpath.JsonPath;
import com.urbantransport.rental.client.CustomerClient;
import com.urbantransport.rental.domain.RentalChannel;
import com.urbantransport.rental.domain.RentalEntity;
import com.urbantransport.rental.domain.RentalSettingsEntity;
import com.urbantransport.rental.domain.RentalStatus;
import com.urbantransport.rental.repository.RentalRepository;
import com.urbantransport.rental.repository.RentalSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@SpringBootTest(properties = "app.jobs.payment-resend.fixed-delay-ms=3600000")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    protected static final ZoneId ZONE = ZoneId.of("America/Guatemala");
    protected static final UUID USER_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    protected static final UUID CUSTOMER_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    protected static final String NIT = "12345678";
    protected static final BigDecimal CONFIRMED_PRICE = new BigDecimal("2400.00");

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected RentalRepository rentalRepository;

    @Autowired
    protected RentalSettingsRepository rentalSettingsRepository;

    @MockitoBean
    protected CustomerClient customerClient;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE rentals, rental_settings, outbox_events, processed_events CASCADE");
    }

    protected static RequestPostProcessor customer() {
        return customer(USER_ID, NIT, CUSTOMER_ID);
    }

    protected static RequestPostProcessor customer(UUID userId, String nit, UUID customerId) {
        return jwt()
                .jwt(token -> token.subject(userId.toString())
                        .claim("roles", List.of("CUSTOMER"))
                        .claim("nit", nit)
                        .claim("customerId", customerId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    protected static RequestPostProcessor branchAdmin(UUID branchId) {
        return userWithRole("BRANCH_ADMIN", branchId);
    }

    protected static RequestPostProcessor systemAdmin() {
        return userWithRole("SYSTEM_ADMIN", null);
    }

    protected static RequestPostProcessor userWithRole(String role, UUID branchId) {
        return jwt()
                .jwt(token -> {
                    token.subject(USER_ID.toString()).claim("roles", List.of(role));
                    if (branchId != null) {
                        token.claim("branchId", branchId.toString());
                    }
                })
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    protected static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    protected static UUID idAt(MvcResult result, String path) throws Exception {
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), path));
    }

    protected void saveSettings(UUID branchId) {
        rentalSettingsRepository.save(RentalSettingsEntity.builder()
                .branchId(branchId)
                .pricePerKm(new BigDecimal("10.00"))
                .passengerSurcharge(new BigDecimal("5.00"))
                .build());
    }

    protected RentalEntity saveRental(UUID branchId, RentalStatus status, LocalDate departure, LocalDate returnDate) {
        return rentalRepository.save(RentalEntity.builder()
                .channel(RentalChannel.ONLINE)
                .customerNit(NIT)
                .customerUserId(USER_ID)
                .branchId(branchId)
                .originText("Parque Central")
                .destinationText("Panajachel")
                .departureDate(departure)
                .returnDate(returnDate)
                .passengers(30)
                .distanceKm(new BigDecimal("120.00"))
                .estimatedPrice(new BigDecimal("1350.00"))
                .confirmedPrice(status == RentalStatus.REQUESTED ? null : CONFIRMED_PRICE)
                .status(status)
                .build());
    }

    protected List<String> outboxEventTypes(UUID aggregateId) {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM outbox_events WHERE aggregate_id = ? ORDER BY created_at",
                String.class, aggregateId);
    }

    protected String outboxPayload(UUID aggregateId, String eventType) {
        return jdbcTemplate.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                String.class, aggregateId, eventType);
    }

    protected int count(String sql, Object... args) {
        Integer total = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return total == null ? 0 : total;
    }
}
