package com.chatapp.message;

import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import java.util.List;
import java.util.Map;

@RegisterRestClient(configKey = "delivery-api")
@Path("/")
public interface DeliveryClient {

    @POST
    @Path("/asymmetric/fetch")
    Map<String, Map<String, String>> fetchAsymmetricKeys(
            @HeaderParam("Authorization") String authorization,
            fetchAsymmetricKeysPayload payload
    );

    record fetchAsymmetricKeysPayload(List<String> UUIDs) {}
}
