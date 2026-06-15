package com.chatapp.message;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
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
    @Path("/")
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

    @POST
    @Path("/ack")
    @Transactional
    public Response acknowledgeMessage(@HeaderParam("Authorization") String authorization, AcknowledgePayload payload) {
        var user = auth.validateToken(authorization);
        if (!user.valid()) {
            logger.warning("Invalid token for message acknowledgment");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Invalid token"))
                    .build();
        }

        // Find the message by message_uuid and recipientDevice
        Message message =
                Message.find("messageUuid = ?1 and recipientDevice = ?2", payload.message_uuid(),
                                payload.recipientDeviceUuid())
                        .firstResult();
        if (message == null) {
            logger.warning("Message not found for acknowledgment: " + payload.message_uuid());
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Message not found for acknowledgment"))
                    .build();
        }

        // Validate that the authenticated user is the actual recipient of this message
        if (!message.recipient.equals(user.userUuid())) {
            logger.warning("User " + user.userUuid() + " is not authorized to acknowledge message " + payload.message_uuid());
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "You are not authorized to acknowledge this message"))
                    .build();
        }

        MessageData messageData = message.messageData;

        // Delete the message to acknowledge receipt
        message.delete();

        // Flush the deletion to the database session before executing the count query
        Message.flush();

        // Cascade delete manually
        if (Message.count("messageData = ?1", messageData) == 0) {
            messageData.delete();
        }

        return Response.ok(Map.of("message", "Message acknowledged successfully"))
                .build();
    }

    public record AcknowledgePayload(String message_uuid, String recipientDeviceUuid) {
    }

    @GET
    @Path("/fetch")
    public Response fetchMessages(@HeaderParam("Authorization") String authorization,
                                 @QueryParam("deviceId") String deviceId) {
        var user = auth.validateToken(authorization);
        if (!user.valid()) {
            logger.warning("Invalid token for message fetch");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Invalid token"))
                    .build();
        }

        List<Message> messages;
        if (deviceId != null && !deviceId.isBlank()) {
            messages = Message.list("recipient = ?1 and recipientDevice = ?2", user.userUuid(), deviceId);
        } else {
            messages = Message.list("recipient = ?1", user.userUuid());
        }

        List<Map<String, Object>> responseMessages = new ArrayList<>();
        for (Message msg : messages) {
            responseMessages.add(Map.of(
                    "message_uuid", msg.messageUuid,
                    "chat_uuid", msg.chatUuid,
                    "sender", msg.sender,
                    "recipient_device", msg.recipientDevice,
                    "timestamp", msg.timestamp,
                    "encrypted_payload", msg.messageData.encryptedPayload
            ));
        }

        return Response.ok(Map.of("messages", responseMessages))
                .build();
    }
}
