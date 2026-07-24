package org.acme;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.net.InetAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Path("/")
public class MeshTestResource {

    @GET
    @Path("/hello")
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        return "Hello from quarkus-mesh-test";
    }

    /**
     * Returns pod-level info that's useful when validating whether traffic
     * actually went through the Envoy sidecar (hostname, headers injected
     * by istio-proxy, etc). Point curl/service calls at this from another
     * pod in the mesh to eyeball mTLS / routing behavior.
     */
    @GET
    @Path("/info")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> info() {
        Map<String, String> data = new HashMap<>();
        try {
            data.put("hostname", InetAddress.getLocalHost().getHostName());
        } catch (Exception e) {
            data.put("hostname", "unknown");
        }
        data.put("timestamp", Instant.now().toString());
        data.put("app", "quarkus-mesh-test");
        return data;
    }
}
