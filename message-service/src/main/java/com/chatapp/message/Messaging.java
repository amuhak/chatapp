package com.chatapp.message;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;


@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class Messaging {
    @RestClient
    @Inject
    Auth auth;

    @RestClient
    @Inject
    IdentityClient identityClient;

    @RestClient
    @Inject
    DeliveryClient deliveryClient;

    private final Logger logger = Logger.getLogger(Messaging.class.getName());

    @POST
    @Path("/message")
    @Transactional
    public Response message(@HeaderParam("Authorization") String authorization, MessagePayload payload) {
        var user = auth.validateToken(authorization);
        if (!user.valid()) {
            logger.warning("Invalid token for message post");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Invalid token"))
                    .build();
        }

        if (payload == null || payload.recipient_uuids() == null || payload.recipient_uuids()
                .isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "recipient_uuids must not be empty"))
                    .build();
        }

        // Create the MessageData entity
        MessageData messageData = new MessageData();
        messageData.encryptedPayload = payload.encrypted_payload();
        messageData.persist();

        // Bulk validate all recipient UUIDs in a single call to identity-service
        Map<String, Boolean> verificationResult = identityClient.checkUsersExist(payload.recipient_uuids());

        // Fetch recipient devices from delivery-service
        Map<String, Map<String, String>> keysResponse;
        try {
            keysResponse =
                    deliveryClient.fetchAsymmetricKeys(authorization,
                            new DeliveryClient.fetchAsymmetricKeysPayload(payload.recipient_uuids()));
        } catch (Exception e) {
            logger.warning("Failed to fetch recipient devices: " + e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Failed to fetch recipient devices: " + e.getMessage()))
                    .build();
        }

        List<Message> messages = new ArrayList<>();

        // Loop over recipients and build Messages list
        for (String recipientUuid : payload.recipient_uuids()) {
            if (verificationResult == null || !verificationResult.getOrDefault(recipientUuid, false)) {
                logger.warning("Recipient " + recipientUuid + " does not exist");
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Recipient user does not exist: " + recipientUuid))
                        .build();
            }

            Map<String, String> devicesMap = keysResponse != null ? keysResponse.get(recipientUuid) : null;
            if (devicesMap == null || devicesMap.isEmpty()) {
                logger.warning("Recipient " + recipientUuid + " has no registered devices");
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Recipient user has no registered devices: " + recipientUuid))
                        .build();
            }

            for (String deviceUuid : devicesMap.keySet()) {
                Message msg = new Message();
                msg.messageData = messageData;
                msg.chatUuid = payload.chat_uuid();
                msg.sender = user.userUuid();
                msg.recipient = recipientUuid;
                msg.recipientDevice = deviceUuid;
                msg.timestamp = Long.parseLong(payload.timestamp());
                messages.add(msg);
            }
        }

        // Persist all Messages in a single bulk operation
        Message.persist(messages);

        return Response.ok(Map.of("message", "Message processed successfully"))
                .build();
    }


    public record MessagePayload(String message_uuid, String chat_uuid, List<String> recipient_uuids,
                                 String encrypted_payload, String timestamp, String sender_device_uuid) {
    }


}
