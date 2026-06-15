package com.chatapp.identity;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class Auth {

    private final Argon2 argon2 = Argon2Factory.create();

    private final ValueCommands<String, String> redisValues;
    private final Logger logger = Logger.getLogger(Auth.class.getName());

    @Inject
    public Auth(RedisDataSource ds) {
        this.redisValues = ds.value(String.class);
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
            redisValues.setex(token, 60 * 60 * 24, dbUser.userUuid);
            return Response.ok(Map.of("message", "Login successful", "token", token, "username", dbUser.username,
                            "userUuid", dbUser.userUuid))
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
                .entity(Map.of("message", "User created", "userUuid", u.userUuid))
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
        String userUuid = redisValues.getdel(token);
        if (userUuid != null) {
            String username = findUsernameByUuid(userUuid);
            logger.log(Level.INFO, (username != null ? username : userUuid) + " logged out");
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
        String userUuid = redisValues.get(token);
        if (userUuid != null) {
            String username = findUsernameByUuid(userUuid);
            return Response.ok(Map.of("valid", true, "username",
                            username != null ? username : userUuid, "userUuid", userUuid))
                    .build();
        } else {
            return Response.ok(Map.of("valid", false))
                    .build();
        }
    }

    private String findUsernameByUuid(String userUuid) {
        return User.find("userUuid", userUuid)
                .firstResultOptional()
                .map(user -> ((User) user).username)
                .orElse(null);
    }

    @GET
    @Path("/user/{uuid}/exists")
    public Response checkUserExists(@PathParam("uuid") String uuid) {
        boolean exists = User.find("userUuid", uuid)
                .firstResultOptional()
                .isPresent();
        return Response.ok(Map.of("exists", exists))
                .build();
    }

    @POST
    @Path("/users/exist")
    public Response checkUsersExist(List<String> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return Response.ok(Map.of()).build();
        }
        List<User> existingUsers = User.list("userUuid in ?1", uuids);
        Set<String> existingUuids = existingUsers.stream()
                .map(user -> user.userUuid)
                .collect(Collectors.toSet());
        Map<String, Boolean> result = uuids.stream()
                .collect(Collectors.toMap(uuid -> uuid, existingUuids::contains));
        return Response.ok(result).build();
    }

    public static class UserInput {
        public String name;
        public String password;
    }
}
