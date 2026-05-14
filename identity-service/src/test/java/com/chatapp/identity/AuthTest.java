package com.chatapp.identity;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthTest {

    private static final String USERNAME = "buzz_gatech";
    private static final String PASSWORD = "secure_password_1885";

    // Tests creating a new user with valid username/password via POST /signup
    // Expect: HTTP 201 and response message "User created"
    @Test
    @Order(1)
    void testSignupSuccess() {
        given().contentType(ContentType.JSON)
                .body(Map.of("name", USERNAME, "password", PASSWORD))
                .when()
                .post("/signup")
                .then()
                .statusCode(201)
                .body("message", is("User created"));
    }

    // Tests signing up a user with a username that already exists
    // Expect: HTTP 409 and error "Username already taken"
    @Test
    @Order(2)
    void testSignupDuplicate() {
        given().contentType(ContentType.JSON)
                .body(Map.of("name", USERNAME, "password", PASSWORD))
                .when()
                .post("/signup")
                .then()
                .statusCode(409)
                .body("error", is("Username already taken"));
    }

    // Parameterized test: invalid signup payloads (empty name, blank password, missing password)
    // Expect: HTTP 400 and error "Username and password must be provided"
    @ParameterizedTest
    @MethodSource("invalidSignupData")
    @Order(3)
    void testSignupInvalidInput(Map<String, String> body) {
        given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/signup")
                .then()
                .statusCode(400)
                .body("error", is("Username and password must be provided"));
    }

    // Provides invalid request bodies for `testSignupInvalidInput`
    private static Stream<Arguments> invalidSignupData() {
        return Stream.of(Arguments.of(Map.of("name", "", "password", PASSWORD)), Arguments.of(Map.of("name", USERNAME
                , "password", "  ")), Arguments.of(Map.of("name", "   ")));
    }

    // Tests successful login with valid credentials via POST /login
    // Expect: HTTP 200, a non-null token field, and message "Login successful"
    @Test
    @Order(4)
    void testLoginSuccess() {
        given().contentType(ContentType.JSON)
                .body(Map.of("name", USERNAME, "password", PASSWORD))
                .when()
                .post("/login")
                .then()
                .statusCode(200)
                .body("token", notNullValue())
                .body("message", is("Login successful"));
    }

    // Tests login with an existing username but wrong password
    // Expect: HTTP 401 and error "Invalid username or password"
    @Test
    @Order(5)
    void testLoginInvalidCredentials() {
        given().contentType(ContentType.JSON)
                .body(Map.of("name", USERNAME, "password", "wrong_password"))
                .when()
                .post("/login")
                .then()
                .statusCode(401)
                .body("error", is("Invalid username or password"));
    }

    // Tests login attempt for a non-existent user
    // Expect: HTTP 401 and error "Invalid username or password"
    @Test
    @Order(6)
    void testLoginUserNotFound() {
        given().contentType(ContentType.JSON)
                .body(Map.of("name", "unknown_user", "password", "any_password"))
                .when()
                .post("/login")
                .then()
                .statusCode(401)
                .body("error", is("Invalid username or password"));
    }

    // Integration flow test: login to obtain a token, logout with that token, then verify the token is invalidated
    // Steps & expectations:
    // 1) POST /login -> HTTP 200 and returns token
    // 2) POST /logout with Bearer token -> HTTP 200 and message "Logged out"
    // 3) POST /logout again with the same token -> HTTP 400 and error "Invalid token"
    @Test
    @Order(7)
    void testLoginAndLogoutFlow() {
        // 1. Login
        String token = given().contentType(ContentType.JSON)
                .body(Map.of("name", USERNAME, "password", PASSWORD))
                .when()
                .post("/login")
                .then()
                .statusCode(200)
                .extract()
                .path("token");

        // 2. Logout
        given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .when()
                .post("/logout")
                .then()
                .statusCode(200)
                .body("message", is("Logged out"));

        // 3. Verify token is gone
        given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .when()
                .post("/logout")
                .then()
                .statusCode(400)
                .body("error", is("Invalid token"));
    }

    // Tests logout endpoint behavior for missing or malformed Authorization headers and invalid tokens
    // Cases covered:
    // - Missing header -> HTTP 400 and error "Missing or malformed Authorization header"
    // - Wrong prefix (e.g. Basic) -> HTTP 400 and same error
    // - "Bearer" with no token -> HTTP 400 and same error
    // - Invalid token after "Bearer " prefix -> HTTP 400 and error "Invalid token"
    @Test
    @Order(8)
    void testLogoutMissingOrMalformedHeader() {
        // Missing header
        given().contentType(ContentType.JSON)
                .when()
                .post("/logout")
                .then()
                .statusCode(400)
                .body("error", is("Missing or malformed Authorization header"));

        // Malformed header (wrong prefix)
        given().contentType(ContentType.JSON)
                .header("Authorization", "Basic dXNlcjpwYXNz")
                .when()
                .post("/logout")
                .then()
                .statusCode(400)
                .body("error", is("Missing or malformed Authorization header"));

        // Malformed header (no token or just prefix)
        given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer")
                .when()
                .post("/logout")
                .then()
                .statusCode(400)
                .body("error", is("Missing or malformed Authorization header"));

        // Malformed header (invalid token format but has prefix)
        given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer unknown-token")
                .when()
                .post("/logout")
                .then()
                .statusCode(400)
                .body("error", is("Invalid token"));
    }

    // Tests that a valid token still resolves to the username even though the stored token state uses UUID
    @Test
    @Order(9)
    void testValidateTokenReturnsUsername() {
        String token = given().contentType(ContentType.JSON)
                .body(Map.of("name", USERNAME, "password", PASSWORD))
                .when()
                .post("/login")
                .then()
                .statusCode(200)
                .extract()
                .path("token");

        given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .when()
                .post("/validate")
                .then()
                .statusCode(200)
                .body("valid", is(true))
                .body("username", is(USERNAME))
                .body("userUuid", notNullValue());
    }
}
