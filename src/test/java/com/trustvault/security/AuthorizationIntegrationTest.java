package com.trustvault.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end authorization tests. Real HTTP, real sessions, real CSRF tokens -
 * no mocked security context, because the bugs this is meant to catch live in
 * the wiring between controller, service and filter chain, and a mocked
 * context would hide them.
 *
 * The critical tests are the IDOR ones: a second user tries every route that
 * touches the first user's document. If the authorization call is removed from
 * the download path, {@link #otherUserCannotDownloadDocument()} fails on its
 * own, which is exactly what the brief asks the tests to demonstrate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthorizationIntegrationTest {

    private static final String DEMO_PASSWORD = "TrustVault#2026";
    private static final String DOCUMENT_BODY = "CONFIDENTIAL salary letter for 2026";
    private static final String DOCUMENT_NAME = "salary-2026.txt";

    @Value("${local.server.port}")
    private int port;

    private Session owner;
    private Session intruder;
    private Session viewer;
    private Session admin;

    private Long documentId;
    private Long intruderId;
    private Long viewerId;
    private String issuedTemporaryPassword;

    @BeforeAll
    void setUp() throws Exception {

        // Seed accounts (DataSeeder runs for the test profile).
        owner = signIn("owner@trustvault.local");
        viewer = signIn("viewer@trustvault.local");
        admin = signIn("admin@trustvault.local");

        // A second, ordinary user who has been granted nothing.
        intruder = register("intruder@test.local", "Intruder");

        HttpResponse<String> me = intruder.get("/api/users/me");
        assertEquals(200, me.statusCode());
        intruderId = Long.parseLong(firstGroup(me.body(), "\"id\"\\s*:\\s*(\\d+)"));

        viewerId = Long.parseLong(firstGroup(
                viewer.get("/api/users/me").body(),
                "\"id\"\\s*:\\s*(\\d+)"));
    }

    // ------------------------------------------------------------------
    // Authentication and CSRF
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    void anonymousRequestIsRejectedWith401() throws Exception {

        Session anonymous = new Session();

        assertEquals(401, anonymous.get("/api/documents").statusCode());
    }

    @Test
    @Order(2)
    void stateChangingRequestWithoutCsrfTokenIsRejected() throws Exception {

        assertEquals(403, owner.postJsonNoCsrf(
                "/api/auth/register",
                "{\"fullName\":\"No Csrf\",\"email\":\"nocsrf@test.local\","
                + "\"password\":\"" + DEMO_PASSWORD + "\"}").statusCode());
    }

    // ------------------------------------------------------------------
    // The owner's document
    // ------------------------------------------------------------------

    @Test
    @Order(3)
    void ownerCanUpload() throws Exception {

        HttpResponse<String> response = owner.upload(
                DOCUMENT_NAME, "HR", DOCUMENT_BODY);

        assertEquals(201, response.statusCode(), response.body());

        documentId = Long.parseLong(
                firstGroup(response.body(), "\"id\"\\s*:\\s*(\\d+)"));

        assertTrue(documentId > 0);
    }

    // ------------------------------------------------------------------
    // IDOR: the intruder tries every route
    // ------------------------------------------------------------------

    @Test
    @Order(10)
    void otherUserCannotSeeTheDocumentInTheirListing() throws Exception {

        HttpResponse<String> response = intruder.get("/api/documents");

        assertEquals(200, response.statusCode());
        assertFalse(response.body().contains(DOCUMENT_NAME),
                "a document granted to nobody must not appear in the listing");
    }

    @Test
    @Order(11)
    void otherUserCannotSearchTheirWayToIt() throws Exception {

        HttpResponse<String> response = intruder.get(
                "/api/documents/search?keyword=salary");

        assertEquals(200, response.statusCode());
        assertFalse(response.body().contains(DOCUMENT_NAME));
    }

    @Test
    @Order(12)
    void otherUserCannotReadTheMetadata() throws Exception {

        assertEquals(403,
                intruder.get("/api/documents/" + documentId).statusCode());
    }

    @Test
    @Order(13)
    void otherUserCannotDownloadDocument() throws Exception {

        HttpResponse<String> response =
                intruder.get("/api/documents/" + documentId + "/download");

        assertEquals(403, response.statusCode());
        assertFalse(response.body().contains("CONFIDENTIAL"),
                "the denied response must not contain the file");
    }

    @Test
    @Order(14)
    void otherUserCannotUpdateDocument() throws Exception {

        HttpResponse<String> response = intruder.putJson(
                "/api/documents/" + documentId,
                "{\"fileName\":\"hijacked.txt\",\"category\":\"HR\"}");

        assertEquals(403, response.statusCode());
    }

    @Test
    @Order(15)
    void otherUserCannotDeleteDocument() throws Exception {

        assertEquals(403,
                intruder.delete("/api/documents/" + documentId).statusCode());

        // And the document is still there for its owner.
        assertEquals(200,
                owner.get("/api/documents/" + documentId).statusCode());
    }

    @Test
    @Order(16)
    void otherUserCannotGrantThemselvesAccess() throws Exception {

        HttpResponse<String> response = intruder.postJson(
                "/api/permissions/grant/" + documentId,
                "{\"targetUserId\":" + intruderId
                        + ",\"permissionType\":\"READ\"}");

        assertEquals(403, response.statusCode());
        assertEquals(403,
                intruder.get("/api/documents/" + documentId + "/download")
                        .statusCode());
    }

    @Test
    @Order(17)
    void otherUserCannotListWhoHasAccess() throws Exception {

        assertEquals(403,
                intruder.get("/api/permissions/" + documentId).statusCode());
    }

    // ------------------------------------------------------------------
    // Grant, then revoke - revocation must be immediate
    // ------------------------------------------------------------------

    @Test
    @Order(20)
    void grantedReadAllowsDownload() throws Exception {

        HttpResponse<String> grant = owner.postJson(
                "/api/permissions/grant/" + documentId,
                "{\"targetUserId\":" + intruderId
                        + ",\"permissionType\":\"READ\"}");

        assertEquals(200, grant.statusCode(), grant.body());

        HttpResponse<String> download =
                intruder.get("/api/documents/" + documentId + "/download");

        assertEquals(200, download.statusCode());
        assertEquals(DOCUMENT_BODY, download.body());

        // The share panel lists the grant; the lazy user association must be
        // readable here, or the owner gets a 500 as soon as one grant exists.
        HttpResponse<String> grants =
                owner.get("/api/permissions/" + documentId);

        assertEquals(200, grants.statusCode(), grants.body());
        assertTrue(grants.body().contains("intruder@test.local"), grants.body());

        // READ must not be enough to edit.
        assertEquals(403, intruder.putJson(
                "/api/documents/" + documentId,
                "{\"fileName\":\"edit.txt\",\"category\":\"HR\"}").statusCode());
    }

    @Test
    @Order(21)
    void revocationTakesEffectOnTheVeryNextRequest() throws Exception {

        HttpResponse<String> revoke = owner.delete(
                "/api/permissions/revoke/" + documentId + "/" + intruderId);

        assertEquals(200, revoke.statusCode(), revoke.body());

        assertEquals(403,
                intruder.get("/api/documents/" + documentId + "/download")
                        .statusCode());

        // The listing no longer offers it either.
        assertFalse(intruder.get("/api/documents").body().contains(DOCUMENT_NAME));
    }

    // ------------------------------------------------------------------
    // Role boundaries
    // ------------------------------------------------------------------

    @Test
    @Order(30)
    void viewerCannotUpload() throws Exception {

        assertEquals(403,
                viewer.upload("viewer-file.txt", "Notes", "hello").statusCode());
    }

    @Test
    @Order(31)
    void administratorCannotReadDocumentContent() throws Exception {

        // Separation of duties: the auditor sees the trail, not the files.
        assertEquals(403,
                admin.get("/api/documents/" + documentId + "/download")
                        .statusCode());
    }

    @Test
    @Order(32)
    void auditTrailIsAdminOnly() throws Exception {

        assertEquals(403, owner.get("/api/admin/audit").statusCode());
        assertEquals(200, admin.get("/api/admin/audit").statusCode());
    }

    // ------------------------------------------------------------------
    // The audit trail must contain the refusals
    // ------------------------------------------------------------------

    @Test
    @Order(40)
    void refusedAttemptsAppearInTheAuditTrail() throws Exception {

        HttpResponse<String> response = admin.get("/api/admin/audit");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"outcome\":\"DENIED\""),
                "refused attempts must be recorded");
        assertTrue(response.body().contains("intruder@test.local"),
                "the record must name who tried");
    }

    @Test
    @Order(41)
    void auditChainVerifiesAfterRealTraffic() throws Exception {

        HttpResponse<String> response = admin.get("/api/admin/audit/verify");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"valid\":true"), response.body());
    }

    // ------------------------------------------------------------------
    // Password reset
    // ------------------------------------------------------------------

    @Test
    @Order(50)
    void resetRequestLooksIdenticalForKnownAndUnknownAccounts()
            throws Exception {

        Session anonymous = new Session();
        anonymous.refreshCsrf();

        HttpResponse<String> known = anonymous.postJson(
                "/api/auth/forgot-password",
                "{\"email\":\"owner@trustvault.local\"}");

        HttpResponse<String> unknown = anonymous.postJson(
                "/api/auth/forgot-password",
                "{\"email\":\"nobody@nowhere.local\"}");

        assertEquals(202, known.statusCode());
        assertEquals(202, unknown.statusCode());
        assertEquals(known.body(), unknown.body(),
                "a different response would reveal which accounts exist");
    }

    @Test
    @Order(51)
    void nonAdministratorCannotIssueATemporaryPassword() throws Exception {

        assertEquals(403, owner.postJson(
                "/api/admin/users/" + viewerId + "/reset-password", "{}")
                .statusCode());
    }

    @Test
    @Order(52)
    void administratorIssuesATemporaryPassword() throws Exception {

        HttpResponse<String> reset = admin.postJson(
                "/api/admin/users/" + viewerId + "/reset-password", "{}");

        assertEquals(200, reset.statusCode(), reset.body());

        String temporaryPassword = firstGroup(
                reset.body(), "\"temporaryPassword\"\\s*:\\s*\"([^\"]+)\"");
        this.issuedTemporaryPassword = temporaryPassword;

        assertNotEquals(DEMO_PASSWORD, temporaryPassword,
                "a reset must not hand back the old password");
        assertTrue(temporaryPassword.length() >= 12);

        Session oldPassword = new Session();
        assertEquals(401,
                oldPassword.attemptSignIn("viewer@trustvault.local", DEMO_PASSWORD)
                        .statusCode(),
                "the previous password must stop working");

        Session newPassword = new Session();
        assertEquals(200,
                newPassword.attemptSignIn("viewer@trustvault.local", temporaryPassword)
                        .statusCode());
    }

    @Test
    @Order(53)
    void userChangesPasswordAndClearsMustChangePasswordFlag() throws Exception {

        assertNotNull(issuedTemporaryPassword, "Temporary password should have been issued in step 52");

        Session viewerSession = new Session();
        assertEquals(200,
                viewerSession.attemptSignIn("viewer@trustvault.local", issuedTemporaryPassword)
                        .statusCode());
        viewerSession.refreshCsrf();

        // 1. Confirm mustChangePassword is true in /api/users/me
        HttpResponse<String> meResponse = viewerSession.get("/api/users/me");
        assertEquals(200, meResponse.statusCode());
        assertTrue(meResponse.body().contains("\"mustChangePassword\":true"),
                "User with temporary password must have mustChangePassword=true");

        // 2. Changing password with wrong current password fails (400)
        HttpResponse<String> wrongCurrent = viewerSession.postJson(
                "/api/users/me/change-password",
                "{\"currentPassword\":\"wrong-old-pwd\",\"newPassword\":\"Permanent#2026!\"}");
        assertEquals(400, wrongCurrent.statusCode());

        // 3. Changing password with correct temporary password succeeds
        HttpResponse<String> correctChange = viewerSession.postJson(
                "/api/users/me/change-password",
                "{\"currentPassword\":\"" + issuedTemporaryPassword + "\",\"newPassword\":\"Permanent#2026!\"}");
        assertEquals(200, correctChange.statusCode(), correctChange.body());

        // 4. Confirm mustChangePassword is now false
        HttpResponse<String> meUpdated = viewerSession.get("/api/users/me");
        assertEquals(200, meUpdated.statusCode());
        assertTrue(meUpdated.body().contains("\"mustChangePassword\":false"),
                "User must have mustChangePassword=false after changing password");

        // 5. Temporary password no longer works
        Session tempAttempt = new Session();
        assertEquals(401,
                tempAttempt.attemptSignIn("viewer@trustvault.local", issuedTemporaryPassword)
                        .statusCode(),
                "Temporary password must stop working after change");

        // 6. New permanent password works
        Session permanentAttempt = new Session();
        assertEquals(200,
                permanentAttempt.attemptSignIn("viewer@trustvault.local", "Permanent#2026!")
                        .statusCode(),
                "New permanent password must work");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Session register(String email, String fullName) throws Exception {

        Session session = new Session();
        session.refreshCsrf();

        HttpResponse<String> response = session.postJson(
                "/api/auth/register",
                "{\"fullName\":\"" + fullName + "\","
                + "\"email\":\"" + email + "\","
                + "\"password\":\"" + DEMO_PASSWORD + "\"}");

        assertEquals(201, response.statusCode(), response.body());

        session.signIn(email);
        return session;
    }

    private Session signIn(String email) throws Exception {
        Session session = new Session();
        session.signIn(email);
        return session;
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String firstGroup(String body, String regex) {

        Matcher matcher = Pattern.compile(regex).matcher(body);

        if (!matcher.find()) {
            fail("Pattern " + regex + " not found in: " + body);
        }

        return matcher.group(1);
    }

    /**
     * One browser-like client: its own cookie jar, its own session, its own
     * CSRF token. Sign-in rotates the CSRF token, so it is re-read afterwards;
     * using the pre-login token is a real 403 that costs an hour to diagnose.
     */
    private final class Session {

        private final CookieManager cookieManager =
                new CookieManager(null, CookiePolicy.ACCEPT_ALL);

        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        private String csrf;

        void refreshCsrf() throws Exception {

            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(), "csrf bootstrap failed");

            csrf = firstGroup(response.body(), "\"token\"\\s*:\\s*\"([^\"]+)\"");
        }

        void signIn(String email) throws Exception {

            HttpResponse<String> response = attemptSignIn(email, DEMO_PASSWORD);

            assertEquals(200, response.statusCode(),
                    "sign-in failed for " + email + ": " + response.body());

            // Session fixation protection and CSRF rotation both replace
            // state on login; fetch the new token before the next write.
            refreshCsrf();
        }

        HttpResponse<String> attemptSignIn(String email, String password)
                throws Exception {

            refreshCsrf();

            return client.send(
                    HttpRequest.newBuilder(uri("/api/auth/login"))
                            .header("Content-Type",
                                    "application/x-www-form-urlencoded")
                            .header("X-XSRF-TOKEN", csrf)
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    formBody(email, password)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> get(String path) throws Exception {
            return client.send(
                    HttpRequest.newBuilder(uri(path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> delete(String path) throws Exception {
            return client.send(
                    HttpRequest.newBuilder(uri(path))
                            .header("X-XSRF-TOKEN", csrf)
                            .DELETE()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> postJson(String path, String json) throws Exception {
            return sendJson("POST", path, json, true);
        }

        HttpResponse<String> postJsonNoCsrf(String path, String json)
                throws Exception {
            return sendJson("POST", path, json, false);
        }

        HttpResponse<String> putJson(String path, String json) throws Exception {
            return sendJson("PUT", path, json, true);
        }

        HttpResponse<String> upload(String fileName, String category,
                                    String content) throws Exception {

            String boundary = "----TrustVaultTestBoundary";

            String body = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"category\"\r\n\r\n"
                    + category + "\r\n"
                    + "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\""
                    + fileName + "\"\r\n"
                    + "Content-Type: text/plain\r\n\r\n"
                    + content + "\r\n"
                    + "--" + boundary + "--\r\n";

            return client.send(
                    HttpRequest.newBuilder(uri("/api/documents/upload"))
                            .header("Content-Type",
                                    "multipart/form-data; boundary=" + boundary)
                            .header("X-XSRF-TOKEN", csrf)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        /**
         * Form bodies must be URL-encoded. A generated temporary password may
         * contain characters that are structural in this encoding, and sending
         * it raw produced a 401 that looked like a wrong password.
         */
        private String formBody(String username, String password) {
            return "username="
                    + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&password="
                    + URLEncoder.encode(password, StandardCharsets.UTF_8);
        }

        private HttpResponse<String> sendJson(String method, String path,
                                              String json, boolean withCsrf)
                throws Exception {

            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", "application/json");

            if (withCsrf) {
                builder.header("X-XSRF-TOKEN", csrf);
            }

            builder.method(method, HttpRequest.BodyPublishers.ofString(json));

            return client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
