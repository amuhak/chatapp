package com.chatapp.delivery;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
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

import jakarta.transaction.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

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
    @Transactional
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

    @POST
    @Path("/symmetric/upload")
    @Transactional
    public Response uploadSymmetricKeys(@HeaderParam("Authorization") String authorization,
                                        SymmetricKeyUploadPayload payload) {
        // Make sure that auth is good
        var user = auth.validateToken(authorization);
        if (!user.valid()) {
            logger.warning("Invalid token for symmetric key upload");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Invalid token"))
                    .build();
        }

        logger.info("Received good symmetric key upload for user UUID: " + user.userUuid());

        if (payload == null || payload.keys()
                .isEmpty()) {
            logger.warning("Empty payload for symmetric key upload");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Payload must contain at least one key"))
                    .build();
        }

        long keyCount = payload.keys()
                .values()
                .stream()
                .mapToLong(Map::size)
                .sum();

        // If sending out keys for more than 1000 devices, it's probably a mistake/spam
        if (keyCount > 1000) {
            logger.warning("Too many keys in symmetric key upload: " + keyCount);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Too many keys in payload. Please limit to 1000 devices per upload."))
                    .build();
        }

        // Get all the recipient devices in one query
        // We do this to prevent having to send a database query for each key
        List<UserDevice> userDevices = UserDevice.list("deviceId in ?1", payload.keys()
                .values()
                .stream()
                .map(Map::keySet)
                .flatMap(Collection::stream)
                .distinct()
                .toList());

        // Create a map from device UUID to UserDevice for quick lookup
        Map<String, UserDevice> deviceUuidToUserDevice = userDevices.stream()
                .collect(Collectors.toMap(device -> device.deviceId,    // Key
                        device -> device,                               // Value
                        (existing, _) -> existing,            // Deal with duplicates
                        HashMap::new                                               // Make hashmap
                ));

        // Get a list of all the encryption keys to add to the db
        List<EncryptionKeys> keys = payload.keys() // Map<recipient_uuid, Map<device_uuid, encrypted_sender_key>>
                .entrySet() // Stream<Map.Entry<recipient_uuid, Map<device_uuid, encrypted_sender_key>>>
                .stream()
                .flatMap((map) -> {
                    var recipientUuid = map.getKey();
                    var deviceMap = map.getValue();
                    return deviceMap.entrySet()
                            .stream()
                            .map((deviceUuidToKeyEntry) -> {
                                String deviceUuid = deviceUuidToKeyEntry.getKey();
                                String encryptedSenderKey = deviceUuidToKeyEntry.getValue();
                                var key = new EncryptionKeys();

                                // Look up the recipient device in the database
                                UserDevice recipientDevice = deviceUuidToUserDevice.get(deviceUuid);
                                if (recipientDevice == null) {
                                    logger.warning("Recipient device not found for UUID: " + deviceUuid);
                                    return null; // Skip this key, but keep processing the rest
                                }

                                // Sanity check
                                if (!recipientDevice.userUUID.equals(recipientUuid)) {
                                    logger.warning("Recipient device UUID " + deviceUuid
                                            + " does not match recipient user UUID " + recipientUuid);
                                    return null; // Skip this key, but keep processing the rest
                                }

                                key.deviceToSendTo = recipientDevice;
                                key.keySenderUserUuid = user.userUuid();
                                key.senderKey = encryptedSenderKey;
                                // Return the key to be added to the database
                                return key;
                            });
                })
                .filter(Objects::nonNull) // Remove any keys that had missing devices
                .toList();


        EncryptionKeys.persist(keys);

        return Response.ok(Map.of("message", "Symmetric keys uploaded successfully"))
                .build();
    }

    @GET
    @Path("/symmetric/fetch")
    public Response fetchSymmetricKeys(@HeaderParam("Authorization") String authorization,
                                       @QueryParam("deviceId") String deviceId) {
        // Make sure that auth is good
        var user = auth.validateToken(authorization);
        if (!user.valid()) {
            logger.warning("Invalid token for symmetric key fetch");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Invalid token"))
                    .build();
        }

        UserDevice device = UserDevice.find("deviceId = ?1 and userUUID = ?2", deviceId, user.userUuid())
                .firstResult();
        if (device == null) {
            logger.warning("Device not found for symmetric key fetch. Device ID: " + deviceId + ", User UUID: "
                    + user.userUuid());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Device not found for user"))
                    .build();
        }

        logger.info("Received good symmetric key fetch for device UUID: " + deviceId);
        // Fetch the encryption keys for the device from the database
        var keys = EncryptionKeys.list("deviceToSendTo.deviceId", deviceId);
        // Hopefully they don't have a billion keys.
        return Response.ok(keys)
                .build();
    }

    @POST
    @Path("/ack")
    @Transactional
    public Response acknowledgeSymmetricKey(@HeaderParam("Authorization") String authorization, SymmetricKeyAck ack) {
        // Make sure that auth is good
        var user = auth.validateToken(authorization);
        if (!user.valid()) {
            logger.warning("Invalid token for symmetric key ack");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Invalid token"))
                    .build();
        }

        if (ack.deviceId() == null || ack.keyUuid() == null) {
            logger.warning("Missing deviceId or keyUuid for symmetric key ack");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Missing deviceId or keyUuid"))
                    .build();
        }

        UserDevice device = UserDevice.find("deviceId = ?1 and userUUID = ?2", ack.deviceId(), user.userUuid())
                .firstResult();
        if (device == null) {
            logger.warning("Device not found for symmetric key ack. Device ID: " + ack.deviceId() + ", User UUID: "
                    + user.userUuid());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Device not found for user"))
                    .build();
        }

        EncryptionKeys key =
                EncryptionKeys.find("uuid = ?1 and deviceToSendTo.deviceId = ?2", ack.keyUuid(), ack.deviceId())
                        .firstResult();
        if (key == null) {
            logger.warning(
                    "Encryption key not found for symmetric key ack. Key UUID: " + ack.keyUuid() + ", Device ID: "
                            + ack.deviceId());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Encryption key not found for device"))
                    .build();
        }

        // If we got here, then the ack is valid. We can delete the key from the database.
        key.delete();
        logger.info("Acknowledged symmetric key with UUID: " + ack.keyUuid() + " for device ID: " + ack.deviceId());
        return Response.ok(Map.of("message", "Symmetric key acknowledged successfully"))
                .build();

    }


    public record KeyUploadPayload(String deviceName, String publicIdentityKey, String publicSignKey) {
    }

    /**
     * JSON in the format of:
     * {"recipient_uuid": {"device_uuid": "encrypted_sender_key"}, ...}, ...}
     */
    public record SymmetricKeyUploadPayload(Map<String, Map<String, String>> keys) {
    }

    public record SymmetricKeyAck(String deviceId, String keyUuid) {
    }
}
