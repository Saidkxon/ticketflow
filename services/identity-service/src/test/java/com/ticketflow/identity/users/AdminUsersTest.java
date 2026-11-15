package com.ticketflow.identity.users;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * FR-2 and ADR-0004: only an Admin lists users and changes roles, a missing role is a plain 403
 * (never a 500), nobody can promote themselves, and an Admin cannot remove their own ADMIN role.
 */
class AdminUsersTest extends AbstractPostgresIntegrationTest {

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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationIdFilter, securityFilterChain)
                .build();
    }

    // ---- who may call

    @Test
    void withoutATokenBothEndpointsAnswer401() throws Exception {
        mockMvc.perform(get("/api/v1/users")).andExpect(status().isUnauthorized());
        mockMvc.perform(rolesRequest(UUID.randomUUID(), "[\"ORGANIZER\"]", null)).andExpect(status().isUnauthorized());
    }

    @Test
    void aCustomerGetsAPlain403NotA500() throws Exception {
        String token = login(register());

        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(rolesRequest(UUID.randomUUID(), "[\"ORGANIZER\"]", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void anOrganizerIsNotAnAdminEither() throws Exception {
        String email = register();
        grant(email, "ORGANIZER");

        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + login(email)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCustomerCannotPromoteThemselves() throws Exception {
        String email = register();
        String token = login(email);

        mockMvc.perform(rolesRequest(idOf(email), "[\"ADMIN\"]", token)).andExpect(status().isForbidden());

        assertThat(rolesOf(email)).containsExactly("CUSTOMER");
    }

    // ---- listing

    @Test
    void anAdminListsUsersInStableOrderWithThePageShape() throws Exception {
        String admin = registerAdmin();
        register();
        register();

        String body = mockMvc.perform(get("/api/v1/users?size=50").header("Authorization", "Bearer " + login(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalItems").isNumber())
                .andExpect(jsonPath("$.items[0].email").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        List<String> created = JsonPath.read(body, "$.items[*].createdAt");
        assertThat(created).hasSizeGreaterThanOrEqualTo(3).isSortedAccordingTo(
                (a, b) -> Instant.parse(a).compareTo(Instant.parse(b)));
        assertThat(body).doesNotContain("bcrypt").doesNotContain("passwordHash");
    }

    @Test
    void theEmailFilterFindsOneUserIgnoringLetterCase() throws Exception {
        String admin = registerAdmin();
        String email = register();

        mockMvc.perform(get("/api/v1/users").param("email", email.toUpperCase())
                        .header("Authorization", "Bearer " + login(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].email").value(email));
        mockMvc.perform(get("/api/v1/users").param("email", "nobody-" + UUID.randomUUID() + "@example.com")
                        .header("Authorization", "Bearer " + login(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void badPagingIsRefusedWith400() throws Exception {
        String token = login(registerAdmin());

        for (String query : new String[] {"size=0", "size=51", "page=-1"}) {
            mockMvc.perform(get("/api/v1/users?" + query).header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    // ---- changing roles

    @Test
    void anAdminGivesAnotherUserTheOrganizerRoleAndCustomerStays() throws Exception {
        String token = login(registerAdmin());
        String email = register();

        mockMvc.perform(rolesRequest(idOf(email), "[\"ORGANIZER\"]", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(2))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andExpect(jsonPath("$.roles[1]").value("ORGANIZER"));

        assertThat(rolesOf(email)).containsExactlyInAnyOrder("CUSTOMER", "ORGANIZER");
    }

    @Test
    void anEmptyListLeavesJustCustomer() throws Exception {
        String token = login(registerAdmin());
        String email = register();
        grant(email, "ORGANIZER");

        mockMvc.perform(rolesRequest(idOf(email), "[]", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
    }

    @Test
    void theNewRoleReachesTheTokenAtTheNextLogin() throws Exception {
        String token = login(registerAdmin());
        String email = register();
        mockMvc.perform(rolesRequest(idOf(email), "[\"ORGANIZER\"]", token)).andExpect(status().isOk());

        String claims = new String(java.util.Base64.getUrlDecoder().decode(login(email).split("\\.")[1]));

        assertThat(claims).contains("ORGANIZER");
    }

    @Test
    void anAdminCannotRemoveTheirOwnAdminRole() throws Exception {
        String email = registerAdmin();
        String token = login(email);

        mockMvc.perform(rolesRequest(idOf(email), "[\"ORGANIZER\"]", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("roles"));
        assertThat(rolesOf(email)).contains("ADMIN");

        // Keeping ADMIN while adding another role is fine.
        mockMvc.perform(rolesRequest(idOf(email), "[\"ADMIN\",\"ORGANIZER\"]", token)).andExpect(status().isOk());
    }

    @Test
    void anAdminCanRemoveAnotherAdminsRole() throws Exception {
        String token = login(registerAdmin());
        String other = registerAdmin();

        mockMvc.perform(rolesRequest(idOf(other), "[]", token)).andExpect(status().isOk());

        assertThat(rolesOf(other)).containsExactly("CUSTOMER");
    }

    @Test
    void invalidBodiesAreRefusedWith400AndNothingChanges() throws Exception {
        String token = login(registerAdmin());
        String email = register();
        UUID id = idOf(email);

        mockMvc.perform(rolesRequest(id, "[\"SUPERUSER\"]", token)).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/users/" + id + "/roles").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(put("/api/v1/users/" + id + "/roles").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[],\"email\":\"x@y.z\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/users/not-a-uuid/roles").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[]}"))
                .andExpect(status().isBadRequest());

        assertThat(rolesOf(email)).containsExactly("CUSTOMER");
    }

    @Test
    void anUnknownUserIsA404() throws Exception {
        String token = login(registerAdmin());

        mockMvc.perform(rolesRequest(UUID.randomUUID(), "[\"ORGANIZER\"]", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void otherPathsUnderUsersAreAdminOnlyByDefault() throws Exception {
        String customer = login(register());

        mockMvc.perform(get("/api/v1/users/" + UUID.randomUUID()).header("Authorization", "Bearer " + customer))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + customer))
                .andExpect(status().isOk());
    }

    // ---- helpers

    private MockHttpServletRequestBuilder rolesRequest(UUID id, String rolesJson, String token) {
        MockHttpServletRequestBuilder request = put("/api/v1/users/" + id + "/roles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":" + rolesJson + "}");
        return token == null ? request : request.header("Authorization", "Bearer " + token);
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

    private String registerAdmin() throws Exception {
        String email = register();
        grant(email, "ADMIN");
        return email;
    }

    private void grant(String email, String role) {
        jdbc.update("insert into user_roles (user_id, role) select id, ? from users where email = ?", role, email);
    }

    private UUID idOf(String email) {
        return jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
    }

    private List<String> rolesOf(String email) {
        return jdbc.queryForList("select role from user_roles where user_id = "
                + "(select id from users where email = ?)", String.class, email);
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
