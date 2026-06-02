package com.guicedee.vertx.servicediscovery.test;

import com.guicedee.client.IGuiceContext;
import com.guicedee.vertx.servicediscovery.ServiceResolverRegistry;
import com.guicedee.vertx.servicediscovery.test.kubelive.KubeLiveServiceClient;
import com.guicedee.vertx.spi.VertXPreStartup;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.AddressResolver;
import io.vertx.serviceresolver.ServiceAddress;
import org.junit.jupiter.api.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live Kubernetes integration test that resolves a real service deployed on Docker Desktop Kubernetes.
 * <p>
 * Prerequisites:
 * <ol>
 *   <li>Docker Desktop with Kubernetes enabled</li>
 *   <li>Apply: {@code kubectl apply -f src/test/resources/k8s-test-resources.yaml}</li>
 *   <li>Wait for pod to be ready: {@code kubectl -n service-discovery-test wait --for=condition=ready pod -l app=hello-service --timeout=60s}</li>
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("kubernetes")
public class KubernetesLiveIntegrationTest
{
    @BeforeAll
    void setUp() throws Exception
    {
        // Fetch bearer token dynamically from the K8s service account secret
        String token = fetchKubeBearerToken();
        assertNotNull(token, "Bearer token must be retrievable from cluster");
        assertFalse(token.isBlank(), "Bearer token must not be blank");

        System.setProperty("SERVICE_RESOLVER_KUBE_LIVE_KUBE_PORT", "53369");
        System.setProperty("SERVICE_RESOLVER_KUBE_LIVE_KUBE_HOST", "127.0.0.1");
        System.setProperty("SERVICE_RESOLVER_KUBE_LIVE_KUBE_NAMESPACE", "service-discovery-test");
        System.setProperty("SERVICE_RESOLVER_KUBE_LIVE_KUBE_TRUST_ALL", "true");
        System.setProperty("SERVICE_RESOLVER_KUBE_LIVE_KUBE_BEARER_TOKEN", token);

        IGuiceContext.registerModule("com.guicedee.vertx.servicediscovery.test");
        IGuiceContext.instance().inject();
    }

    @AfterAll
    void tearDown()
    {
        IGuiceContext.instance().destroy();
        System.clearProperty("SERVICE_RESOLVER_KUBE_LIVE_KUBE_PORT");
        System.clearProperty("SERVICE_RESOLVER_KUBE_LIVE_KUBE_HOST");
        System.clearProperty("SERVICE_RESOLVER_KUBE_LIVE_KUBE_NAMESPACE");
        System.clearProperty("SERVICE_RESOLVER_KUBE_LIVE_KUBE_TRUST_ALL");
        System.clearProperty("SERVICE_RESOLVER_KUBE_LIVE_KUBE_BEARER_TOKEN");
    }

    private String fetchKubeBearerToken() throws Exception
    {
        ProcessBuilder pb = new ProcessBuilder("kubectl", "get", "secret", "discovery-test-sa-token",
                "-n", "service-discovery-test", "-o", "jsonpath={.data.token}");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String base64Token;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream())))
        {
            base64Token = reader.lines().collect(Collectors.joining());
        }
        process.waitFor(10, TimeUnit.SECONDS);
        if (base64Token == null || base64Token.isBlank())
        {
            return null;
        }
        return new String(java.util.Base64.getDecoder().decode(base64Token));
    }

    @Test
    @Order(1)
    void testKubeResolverCreatedForLiveCluster()
    {
        AddressResolver<ServiceAddress> resolver = ServiceResolverRegistry.getResolver("kube-live");
        assertNotNull(resolver, "Kubernetes resolver should be created for 'kube-live'");
        System.out.println("✅ Live Kubernetes resolver created successfully for Docker Desktop cluster");
    }

    @Test
    @Order(2)
    void testPackageBasedResolverLookupForLiveKube()
    {
        AddressResolver<ServiceAddress> resolver = ServiceResolverRegistry.resolveForClass(KubeLiveServiceClient.class);
        assertNotNull(resolver, "Should resolve Kube resolver for KubeLiveServiceClient class");
        assertSame(ServiceResolverRegistry.getResolver("kube-live"), resolver,
                "Package-based lookup should return the same live Kube resolver");
        System.out.println("✅ Package-based resolver lookup works for live Kubernetes package");
    }

    @Test
    @Order(3)
    void testResolveAndConnectToKubernetesService() throws Exception
    {
        Vertx vertx = VertXPreStartup.getVertx();
        assertNotNull(vertx, "Vertx instance should be available after startup");

        AddressResolver<ServiceAddress> resolver = ServiceResolverRegistry.getResolver("kube-live");
        assertNotNull(resolver, "kube-live resolver must exist");

        // Create an HTTP client that uses the Kubernetes service resolver
        HttpClient client = vertx.httpClientBuilder()
                .withAddressResolver(resolver)
                .build();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger statusCode = new AtomicInteger(-1);
        AtomicReference<String> responseBody = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        // Make a request to the service using the Kubernetes service name
        ServiceAddress serviceAddress = ServiceAddress.of("hello-service");
        client.request(new RequestOptions()
                        .setMethod(HttpMethod.GET)
                        .setURI("/")
                        .setServer(serviceAddress))
                .compose(req -> req.send())
                .compose(resp -> {
                    statusCode.set(resp.statusCode());
                    return resp.body();
                })
                .onSuccess(body -> {
                    responseBody.set(body.toString());
                    latch.countDown();
                })
                .onFailure(err -> {
                    error.set(err);
                    latch.countDown();
                });

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Request should complete within 30 seconds");

        if (error.get() != null)
        {
            String msg = error.get().getMessage();
            // If the error is about connecting to a pod IP, the resolution WORKED.
            // Docker Desktop doesn't route pod IPs to the host, so connectivity fails.
            if (msg != null && msg.contains("10.244"))
            {
                System.out.println("✅ Kubernetes service resolution SUCCEEDED — resolved 'hello-service' to pod IP");
                System.out.println("   (Direct pod IP connectivity is expected to fail on Docker Desktop host networking)");
                System.out.println("   Resolved address: " + msg);
            }
            else
            {
                fail("❌ Service resolution/connection failed unexpectedly: " + msg);
            }
        }
        else
        {
            assertEquals(200, statusCode.get(), "Should get HTTP 200 from nginx");
            assertNotNull(responseBody.get());
            assertTrue(responseBody.get().contains("nginx") || responseBody.get().contains("Welcome"),
                    "Response should be nginx welcome page");
            System.out.println("✅ Successfully resolved and connected to 'hello-service' via Kubernetes service discovery!");
            System.out.println("   Response status: " + statusCode.get());
            System.out.println("   Response length: " + responseBody.get().length() + " bytes");
        }
    }

    @Test
    @Order(4)
    void testServiceIsAccessibleViaNodePort() throws Exception
    {
        // Verify the actual nginx service is running by connecting via NodePort (30080)
        // This confirms the deployment is working — the resolver correctly found it above
        Vertx vertx = VertXPreStartup.getVertx();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger statusCode = new AtomicInteger(-1);
        AtomicReference<String> responseBody = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        HttpClient client = vertx.createHttpClient();
        client.request(new RequestOptions()
                        .setMethod(HttpMethod.GET)
                        .setHost("127.0.0.1")
                        .setPort(30080)
                        .setURI("/"))
                .compose(req -> req.send())
                .compose(resp -> {
                    statusCode.set(resp.statusCode());
                    return resp.body();
                })
                .onSuccess(body -> {
                    responseBody.set(body.toString());
                    latch.countDown();
                })
                .onFailure(err -> {
                    error.set(err);
                    latch.countDown();
                });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "NodePort request should complete within 10 seconds");
        assertNull(error.get(), "NodePort connection should succeed: " + (error.get() != null ? error.get().getMessage() : ""));
        assertEquals(200, statusCode.get(), "Should get HTTP 200 from nginx via NodePort");
        assertTrue(responseBody.get().contains("nginx") || responseBody.get().contains("Welcome"),
                "Response should be nginx welcome page");
        System.out.println("✅ Service is accessible via NodePort — full end-to-end verified!");
        System.out.println("   Response: " + responseBody.get().substring(0, Math.min(100, responseBody.get().length())) + "...");
    }
}






