package com.chatapp.delivery;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.logging.Logger;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class Delivery {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(Delivery.class);
    @RestClient
    @Inject
    Auth auth;

    private final Logger logger = Logger.getLogger(Delivery.class.getName());

    @POST
    @Path("/asymmetric/upload")
    public Response uploadAsymmetricKey(@HeaderParam("Authorization") String authorization, KeyUploadPayload payload) {
        // Make sure that auth is good
        var user = auth.validateToken(authorization);
        if (!user.valid()) {
            logger.warning("Invalid token for asymmetric key upload");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Invalid token"))
                    .build();
        }
        logger.info("Received good asymmetric key upload for device: " + payload.deviceName());
        // Check how many devices the user has, limit to 100
        var deviceCount = UserDevice.count("userUUID", user.userUuid());
        if (deviceCount >= 100) {
            logger.warning("User " + user.username() + " has too many devices (" + deviceCount + ")");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Device limit reached.", "Pretty Print", "You have reached the maximum "
                            + "number of devices allowed. Please remove some devices before adding new ones."))
                    .build();
        }

        // Save the device info and keys to the database
        UserDevice device = new UserDevice();
        device.userUUID = user.userUuid();
        device.deviceName = payload.deviceName();
        device.publicIdentityKey = payload.publicIdentityKey();
        device.publicSignKey = payload.publicSignKey();
        device.persist();
        return Response.ok(Map.of("message", "Asymmetric keys uploaded successfully", "deviceId", device.deviceId))
                .build();
    }

    @GET
    @Path("/asymmetric/fetch")
    public Response fetchAsymmetricKeys(@HeaderParam("Authorization") String authorization,
                                        @QueryParam("UUID") String UserUuid) {
        // Make sure that auth is good
        var user = auth.validateToken(authorization);
        if (!user.valid()) {
            logger.warning("Invalid token for asymmetric key fetch");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Invalid token"))
                    .build();
        }
        logger.info("Received good asymmetric key fetch for user UUID: " + UserUuid);
        // Fetch the device info and keys from the database
        var devices = UserDevice.list("userUUID", UserUuid);
        // Hopefully they don't have a billion devices.
        return Response.ok(devices)
                .build();
    }

    public record KeyUploadPayload(String deviceName, String publicIdentityKey, String publicSignKey) {
    }
}
