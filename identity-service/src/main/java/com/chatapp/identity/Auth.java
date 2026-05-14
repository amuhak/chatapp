package com.chatapp.identity;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MediaType.*;
import jakarta.ws.rs.core.Response;
import jakarta.transaction.Transactional;
import jdk.jfr.ContentType;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class Auth {

    private final Argon2 argon2 = Argon2Factory.create();

    private final ValueCommands<String, String> countCommands;
    private final Logger logger = Logger.getLogger(Auth.class.getName());

    @Inject
    public Auth(RedisDataSource ds) {
        this.countCommands = ds.value(String.class);
    }

    @POST
    @Path("/login")
    public Response login(UserInput input) {
        Optional<User> userOpt = User.find("username", input.name)
                .firstResultOptional();

        if (userOpt.isEmpty()) {
            // Dummy hash to mitigate timing attacks
            argon2.hash(6, 65536, 2, "dummyHash".toCharArray());
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Invalid username or password"))
                    .build();
        }

        User dbUser = userOpt.get();

        boolean success = argon2.verify(dbUser.passwordHash, input.password.toCharArray());

        if (success) {
            String token = UUID.randomUUID()
                    .toString();
            // One day expiration for the token
            countCommands.setex(token, 60 * 60 * 24, input.name);
            return Response.ok(Map.of("message", "Login successful", "token", token))
                    .build();
        } else {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Invalid username or password"))
                    .build();
        }
    }

    @POST
    @Path("/signup")
    @Transactional
    public Response signup(UserInput input) {
        if (input == null || input.name == null || input.name.isBlank() || input.password == null
                || input.password.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Username and password must be provided"))
                    .build();
        }

        // Check if user already exists
        Optional<User> existing = User.find("username", input.name)
                .firstResultOptional();
        if (existing.isPresent()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Username already taken"))
                    .build();
        }

        // Hash the password and persist the new user
        String hash = argon2.hash(6, 65536, 2, input.password.toCharArray());
        User u = new User();
        u.username = input.name;
        u.passwordHash = hash;
        // Persist - using Panache entity-style persist
        u.persist();

        return Response.status(Response.Status.CREATED)
                .entity(Map.of("message", "User created"))
                .build();
    }

    @POST
    @Path("/logout")
    public Response logout(@HeaderParam("Authorization") String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Missing or malformed Authorization header"))
                    .build();
        }

        String token = auth.substring("Bearer ".length())
                .trim();
        String user = countCommands.getdel(token);
        if (user != null) {
            logger.log(Level.INFO, user + " logged out");
            return Response.ok(Map.of("message", "Logged out"))
                    .build();
        } else {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid token"))
                    .build();
        }
    }

    @Path("/validate")
    @POST
    public Response validateToken(@HeaderParam("Authorization") String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Missing or malformed Authorization header"))
                    .build();
        }

        String token = auth.substring("Bearer ".length())
                .trim();
        String user = countCommands.get(token);
        if (user != null) {
            return Response.ok(Map.of("valid", true, "username", user))
                    .build();
        } else {
            return Response.ok(Map.of("valid", false))
                    .build();
        }
    }

    public static class UserInput {
        public String name;
        public String password;
    }
}
