package com.ticketflow.identity.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ticketflow.identity.AbstractPostgresIntegrationTest;
import com.ticketflow.identity.web.CorrelationIdFilter;
import jakarta.servlet.Filter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * ADR-0015: consent levels, position history, retention, the Admin reads with their access log,
 * and who may call what.
 */
class LocationTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter securityFilterChain;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LocationService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationIdFilter, securityFilterChain)
                .build();
    }

    // ---- who may call

    @Test
    void withoutATokenEveryLocationPathAnswers401() throws Exception {
        mockMvc.perform(get("/api/v1/location/consent")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/location/consent").contentType(MediaType.APPLICATION_JSON)
                .content("{\"level\":\"FULL\",\"textVersion\":1}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/location/positions").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/location/latest")).andExpect(status().isUnauthorized());
    }

    @Test
    void aCustomerCannotReadOtherPeoplesLocation() throws Exception {
        String token = login(register());

        mockMvc.perform(get("/api/v1/admin/location/latest").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/admin/location/users/" + UUID.randomUUID() + "/history")
                .header("Authorization", bearer(token))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/anything-new").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    // ---- consent

    @Test
    void aNewUserHasNotAnswered() throws Exception {
        String token = login(register());

        mockMvc.perform(get("/api/v1/location/consent").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").doesNotExist())
                .andExpect(jsonPath("$.needsAnswer").value(true))
                .andExpect(jsonPath("$.currentTextVersion").value(1));
    }

    @Test
    void theAnswerIsStoredWithTheTextVersion() throws Exception {
        String email = register();
        String token = login(email);

        answer(token, "FULL", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("FULL"))
                .andExpect(jsonPath("$.textVersion").value(1))
                .andExpect(jsonPath("$.needsAnswer").value(false));

        assertThat(jdbc.queryForObject("select level from location_consents where user_id = ?",
                String.class, idOf(email))).isEqualTo("FULL");
        answer(token, "OFF", 1).andExpect(status().isOk()).andExpect(jsonPath("$.level").value("OFF"));
        assertThat(jdbc.queryForObject("select count(*) from location_consents where user_id = ?",
                Integer.class, idOf(email))).isEqualTo(1);
    }

    @Test
    void anAnswerForAnotherTextVersionIs409() throws Exception {
        String token = login(register());

        answer(token, "FULL", 99)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONSENT_TEXT_OUTDATED"));
    }

    @Test
    void badConsentBodiesAre400() throws Exception {
        String token = login(register());

        for (String body : new String[] {"{}", "{\"level\":\"FULL\"}", "{\"textVersion\":1}",
                "{\"level\":\"MAYBE\",\"textVersion\":1}", "{\"level\":\"FULL\",\"textVersion\":1,\"userId\":\"x\"}"}) {
            mockMvc.perform(put("/api/v1/location/consent").header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void switchingOffDeletesTheWholeHistory() throws Exception {
        String email = register();
        String token = login(email);
        UUID id = idOf(email);
        answer(token, "FULL", 1).andExpect(status().isOk());
        insertPoint(id, Instant.now().minus(2, ChronoUnit.DAYS));
        insertPoint(id, Instant.now().minus(1, ChronoUnit.HOURS));

        answer(token, "OFF", 1).andExpect(status().isOk());

        assertThat(pointCount(id)).isZero();
    }

    @Test
    void switchingFromFullToMinimumDeletesPointsOlderThanFiveDays() throws Exception {
        String email = register();
        String token = login(email);
        UUID id = idOf(email);
        answer(token, "FULL", 1).andExpect(status().isOk());
        insertPoint(id, Instant.now().minus(6, ChronoUnit.DAYS));
        insertPoint(id, Instant.now().minus(1, ChronoUnit.DAYS));

        answer(token, "MINIMUM", 1).andExpect(status().isOk());

        assertThat(pointCount(id)).isEqualTo(1);
    }

    // ---- positions

    @Test
    void aPositionNeedsAnAnswerOtherThanOff() throws Exception {
        String email = register();
        String token = login(email);

        sendPoint(token, 41.31, 69.28, 12.0, Instant.now())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LOCATION_CONSENT_REQUIRED"));
        answer(token, "OFF", 1).andExpect(status().isOk());
        sendPoint(token, 41.31, 69.28, 12.0, Instant.now())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LOCATION_CONSENT_REQUIRED"));

        assertThat(pointCount(idOf(email))).isZero();
    }

    @Test
    void anAnswerForAnotherTextVersionStopsTracking() throws Exception {
        String email = register();
        String token = login(email);
        jdbc.update("insert into location_consents (user_id, level, text_version, decided_at) values (?, 'FULL', 2, now())",
                idOf(email));

        sendPoint(token, 41.31, 69.28, 12.0, Instant.now()).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/location/consent").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.level").value("FULL"))
                .andExpect(jsonPath("$.needsAnswer").value(true));
    }

    @Test
    void pointsCloserThanThirtySecondsAreIgnored() throws Exception {
        String email = register();
        String token = login(email);
        answer(token, "MINIMUM", 1).andExpect(status().isOk());
        Instant base = Instant.now().minusSeconds(600);

        sendPoint(token, 41.31, 69.28, 12.0, base).andExpect(status().isCreated());
        sendPoint(token, 41.32, 69.29, 12.0, base.plusSeconds(10)).andExpect(status().isAccepted());
        sendPoint(token, 41.30, 69.27, 12.0, base.minusSeconds(60)).andExpect(status().isAccepted());
        sendPoint(token, 41.33, 69.30, 12.0, base.plusSeconds(40)).andExpect(status().isCreated());

        assertThat(pointCount(idOf(email))).isEqualTo(2);
    }

    @Test
    void invalidPointsAre400AndNothingIsStored() throws Exception {
        String email = register();
        String token = login(email);
        answer(token, "FULL", 1).andExpect(status().isOk());
        Instant now = Instant.now();

        sendPoint(token, 91.0, 69.28, 12.0, now).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("latitude"));
        sendPoint(token, 41.0, 181.0, 12.0, now).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("longitude"));
        sendPoint(token, 41.0, 69.0, -1.0, now).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("accuracyMeters"));
        sendPoint(token, 41.0, 69.0, 12.0, now.plusSeconds(3600)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("recordedAt"));
        mockMvc.perform(post("/api/v1/location/positions").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(4));

        assertThat(pointCount(idOf(email))).isZero();
    }

    // ---- retention

    @Test
    void retentionKeepsThirtyDaysForFullAndFiveForMinimumAndIsIdempotent() throws Exception {
        String fullEmail = register();
        String minimumEmail = register();
        UUID full = idOf(fullEmail);
        UUID minimum = idOf(minimumEmail);
        answer(login(fullEmail), "FULL", 1).andExpect(status().isOk());
        answer(login(minimumEmail), "MINIMUM", 1).andExpect(status().isOk());
        insertPoint(full, Instant.now().minus(31, ChronoUnit.DAYS));
        insertPoint(full, Instant.now().minus(29, ChronoUnit.DAYS));
        insertPoint(minimum, Instant.now().minus(6, ChronoUnit.DAYS));
        insertPoint(minimum, Instant.now().minus(4, ChronoUnit.DAYS));

        service.deleteExpired();

        assertThat(pointCount(full)).isEqualTo(1);
        assertThat(pointCount(minimum)).isEqualTo(1);
        service.deleteExpired();
        assertThat(pointCount(full)).isEqualTo(1);
        assertThat(pointCount(minimum)).isEqualTo(1);
    }

    @Test
    void retentionAlsoRemovesPointsOfUsersWhoAreNotTracked() throws Exception {
        UUID id = idOf(register());
        insertPoint(id, Instant.now().minus(1, ChronoUnit.HOURS));

        service.deleteExpired();

        assertThat(pointCount(id)).isZero();
    }

    // ---- Admin reads

    @Test
    void theAdminSeesTheNewestPointOfEveryConsentingUserAndTheReadIsLogged() throws Exception {
        String adminEmail = registerAdmin();
        String adminToken = login(adminEmail);
        String fullEmail = register();
        String minimumEmail = register();
        String offEmail = register();
        UUID full = idOf(fullEmail);
        UUID minimum = idOf(minimumEmail);
        UUID off = idOf(offEmail);
        answer(login(fullEmail), "FULL", 1).andExpect(status().isOk());
        answer(login(minimumEmail), "MINIMUM", 1).andExpect(status().isOk());
        answer(login(offEmail), "OFF", 1).andExpect(status().isOk());
        insertPoint(full, Instant.now().minus(2, ChronoUnit.HOURS), 41.0, 69.0);
        insertPoint(full, Instant.now().minus(1, ChronoUnit.HOURS), 41.5, 69.5);
        insertPoint(minimum, Instant.now().minus(1, ChronoUnit.HOURS), 42.0, 70.0);
        insertPoint(off, Instant.now().minus(1, ChronoUnit.HOURS), 43.0, 71.0);

        String body = mockMvc.perform(get("/api/v1/admin/location/latest").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> ids = JsonPath.read(body, "$.items[*].userId");
        assertThat(ids).contains(full.toString(), minimum.toString()).doesNotContain(off.toString());
        assertThat(ids.stream().filter(full.toString()::equals).count()).isEqualTo(1);
        List<Double> fullLatitude = JsonPath.read(body, "$.items[?(@.userId=='" + full + "')].latitude");
        assertThat(fullLatitude).containsExactly(41.5);
        List<String> names = JsonPath.read(body, "$.items[?(@.userId=='" + full + "')].displayName");
        assertThat(names).containsExactly("Test User");
        List<String> levels = JsonPath.read(body, "$.items[?(@.userId=='" + minimum + "')].level");
        assertThat(levels).containsExactly("MINIMUM");

        assertThat(jdbc.queryForObject("select count(*) from location_access_log "
                        + "where admin_id = ? and subject_user_id is null and kind = 'LATEST_ALL'",
                Integer.class, idOf(adminEmail))).isEqualTo(1);
    }

    @Test
    void theAdminReadsTheHistoryOfOneUserNewestFirstAndItIsLogged() throws Exception {
        String adminEmail = registerAdmin();
        String adminToken = login(adminEmail);
        String email = register();
        UUID id = idOf(email);
        answer(login(email), "FULL", 1).andExpect(status().isOk());
        insertPoint(id, Instant.now().minus(3, ChronoUnit.HOURS), 41.0, 69.0);
        insertPoint(id, Instant.now().minus(2, ChronoUnit.HOURS), 41.1, 69.1);
        insertPoint(id, Instant.now().minus(1, ChronoUnit.HOURS), 41.2, 69.2);

        mockMvc.perform(get("/api/v1/admin/location/users/" + id + "/history")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Test User"))
                .andExpect(jsonPath("$.level").value("FULL"))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].latitude").value(41.2))
                .andExpect(jsonPath("$.items[2].latitude").value(41.0));
        mockMvc.perform(get("/api/v1/admin/location/users/" + id + "/history?limit=1")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));

        assertThat(jdbc.queryForObject("select count(*) from location_access_log "
                        + "where admin_id = ? and subject_user_id = ? and kind = 'HISTORY'",
                Integer.class, idOf(adminEmail), id)).isEqualTo(2);
    }

    @Test
    void historyOfAnUnknownUserOrOfSomeoneWhoSaidNoIs404AndNotLogged() throws Exception {
        String adminEmail = registerAdmin();
        String adminToken = login(adminEmail);
        String offEmail = register();
        answer(login(offEmail), "OFF", 1).andExpect(status().isOk());
        String neverAsked = register();

        for (UUID id : new UUID[] {UUID.randomUUID(), idOf(offEmail), idOf(neverAsked)}) {
            mockMvc.perform(get("/api/v1/admin/location/users/" + id + "/history")
                            .header("Authorization", bearer(adminToken)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("LOCATION_NOT_AVAILABLE"));
        }

        assertThat(jdbc.queryForObject("select count(*) from location_access_log where admin_id = ?",
                Integer.class, idOf(adminEmail))).isZero();
    }

    @Test
    void badHistoryParametersAre400() throws Exception {
        String adminToken = login(registerAdmin());
        String url = "/api/v1/admin/location/users/" + UUID.randomUUID() + "/history";

        mockMvc.perform(get(url + "?limit=0").header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(url + "?limit=1001").header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(url + "?from=2026-02-01T00:00:00Z&to=2026-01-01T00:00:00Z")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(url + "?from=yesterday").header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());
    }

    // ---- the database

    @Test
    void deletingAUserDeletesTheirLocationData() throws Exception {
        String adminEmail = registerAdmin();
        String adminToken = login(adminEmail);
        String email = register();
        UUID id = idOf(email);
        answer(login(email), "FULL", 1).andExpect(status().isOk());
        insertPoint(id, Instant.now().minus(1, ChronoUnit.HOURS));
        mockMvc.perform(get("/api/v1/admin/location/users/" + id + "/history")
                .header("Authorization", bearer(adminToken))).andExpect(status().isOk());

        jdbc.update("delete from users where id = ?", id);

        assertThat(pointCount(id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from location_consents where user_id = ?",
                Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from location_access_log where subject_user_id = ?",
                Integer.class, id)).isZero();
    }

    @Test
    void theDatabaseRefusesBadValues() {
        UUID id = idOf(registerQuietly());

        assertThatThrownBy(() -> jdbc.update("insert into location_consents values (?, 'MAYBE', 1, now())", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into location_positions "
                + "values (gen_random_uuid(), ?, 91, 0, 1, now(), now())", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into location_positions "
                + "values (gen_random_uuid(), ?, 0, 181, 1, now(), now())", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into location_positions "
                + "values (gen_random_uuid(), ?, 0, 0, -1, now(), now())", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- helpers

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private ResultActions answer(String token, String level, int textVersion) throws Exception {
        return mockMvc.perform(put("/api/v1/location/consent").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"level\":\"" + level + "\",\"textVersion\":" + textVersion + "}"));
    }

    private ResultActions sendPoint(String token, double lat, double lng, double accuracy, Instant at)
            throws Exception {
        return mockMvc.perform(post("/api/v1/location/positions").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":" + lat + ",\"longitude\":" + lng + ",\"accuracyMeters\":" + accuracy
                        + ",\"recordedAt\":\"" + at + "\"}"));
    }

    private void insertPoint(UUID userId, Instant recordedAt) {
        insertPoint(userId, recordedAt, 41.3, 69.2);
    }

    private void insertPoint(UUID userId, Instant recordedAt, double lat, double lng) {
        jdbc.update("insert into location_positions (id, user_id, latitude, longitude, accuracy_m, recorded_at, "
                        + "received_at) values (?, ?, ?, ?, 10, ?, now())",
                UUID.randomUUID(), userId, lat, lng, java.sql.Timestamp.from(recordedAt));
    }

    private int pointCount(UUID userId) {
        return jdbc.queryForObject("select count(*) from location_positions where user_id = ?", Integer.class, userId);
    }

    private String register() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                                + "\",\"displayName\":\"Test User\"}"))
                .andExpect(status().isCreated());
        return email;
    }

    private String registerQuietly() {
        try {
            return register();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String registerAdmin() throws Exception {
        String email = register();
        jdbc.update("insert into user_roles (user_id, role) select id, 'ADMIN' from users where email = ?", email);
        return email;
    }

    private UUID idOf(String email) {
        return jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
    }

    private String login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
