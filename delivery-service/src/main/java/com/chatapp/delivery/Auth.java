package com.chatapp.delivery;

import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "identity-api")
@Path("/")
public interface Auth {

    @POST
    @Path("/validate")
    ValidationResponse validateToken(@HeaderParam("Authorization") String authHeader);

    record ValidationResponse(boolean valid, String username, String userUuid) {}
}
