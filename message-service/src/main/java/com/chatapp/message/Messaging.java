package com.chatapp.message;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;


@Path("/")
public class Messaging {
    @RestClient
    @Inject
    Auth auth;

    private final Logger logger = Logger.getLogger(Messaging.class.getName());

    @POST
    @Path("/message")
    public Response message(@HeaderParam("Authorization") String authorization, MessagePayload payload) {
        var user = auth.validateToken(authorization);
        if (!user.valid()) {
            logger.warning("Invalid token for asymmetric key upload");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Invalid token"))
                    .build();
        }


    }


    public record MessagePayload(String message_uuid, String chat_uuid, List<String> recipient_uuids,
                                 String encrypted_payload, String timestamp, String sender_device_uuid) {
    }
}
