package com.chatapp.message;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import java.util.List;
import java.util.Map;

@RegisterRestClient(configKey = "identity-api")
@Path("/")
public interface IdentityClient {

    @GET
    @Path("/user/{uuid}/exists")
    Map<String, Boolean> checkUserExists(@PathParam("uuid") String uuid);

    @POST
    @Path("/users/exist")
    Map<String, Boolean> checkUsersExist(List<String> uuids);
}
