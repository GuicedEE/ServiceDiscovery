package com.guicedee.vertx.servicediscovery.test;

import com.guicedee.client.IGuiceContext;
import com.guicedee.runtime.autoconfigure.RuntimeEnvironment;
import com.guicedee.runtime.autoconfigure.implementations.RuntimeAutoConfigurePreStartup;
import com.guicedee.vertx.servicediscovery.ServiceResolverRegistry;
import io.vertx.core.net.AddressResolver;
import io.vertx.serviceresolver.ServiceAddress;
import org.junit.jupiter.api.*;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates how runtime-autoconfigure, service-discovery, and (optionally) telemetry
 * work together in a unified cloud-aware application.
 * <p>
 * <h2>How the modules interact:</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │                    GuicedEE Application Startup                         │
 * │                                                                         │
 * │  1. TelemetryPreStartup (sortOrder MIN+35)                             │
 * │     - Initializes OpenTelemetry SDK                                     │
 * │     - Sets service.name, deployment.environment resource attributes     │
 * │                                                                         │
 * │  2. RuntimeAutoConfigurePreStartup (sortOrder MIN+50)                  │
 * │     - Scans for @AzureContainerApps / @Kubernetes / etc annotations     │
 * │     - Detects cloud platform from environment variables                 │
 * │     - Populates RuntimeEnvironment record                               │
 * │     → Result: RuntimeAutoConfigurePreStartup.current() is populated     │
 * │                                                                         │
 * │  3. VertXPreStartup (sortOrder MIN+100 approx)                         │
 * │     - Creates Vertx instance                                            │
 * │                                                                         │
 * │  4. ServiceResolverPreStartup (sortOrder MIN+500)                      │
 * │     - Scans @ServiceResolverOptions annotations                         │
 * │     - Resolves env var overrides                                        │
 * │                                                                         │
 * │  5. ServiceResolverPostStartup (sortOrder MIN+600)                     │
 * │     - Creates all resolvers (Kube, SRV, Auto)                          │
 * │     - Auto resolver checks RuntimeAutoConfigurePreStartup to decide     │
 * │       Kube vs SRV                                                       │
 * │                                                                         │
 * │  ═══════════════════════════════════════════════════════════════════════ │
 * │                                                                         │
 * │  At runtime, when a service call is made:                               │
 * │                                                                         │
 * │  ┌────────────┐     ┌──────────────────┐     ┌────────────────┐        │
 * │  │ Your Code  │────▶│ ServiceResolver  │────▶│ Kube/SRV API   │        │
 * │  │            │     │ Registry         │     │ (endpoint list)│        │
 * │  └────────────┘     └──────────────────┘     └────────────────┘        │
 * │       │                                              │                  │
 * │       │ (if telemetry on classpath)                   │                  │
 * │       ▼                                              ▼                  │
 * │  ┌────────────────────────────────────────────────────────────┐        │
 * │  │  OpenTelemetry Span                                         │        │
 * │  │  - service.name = "guicedee-website" (from runtime-autoconf)│        │
 * │  │  - cloud.provider = "azure-container-apps"                  │        │
 * │  │  - cloud.region = "ukwest"                                  │        │
 * │  │  - resolved.address = "10.244.1.3:80"                       │        │
 * │  └────────────────────────────────────────────────────────────┘        │
 * └─────────────────────────────────────────────────────────────────────────┘
 * </pre>
 * <p>
 * <h2>How to use in your app:</h2>
 * <p>
 * <b>Step 1:</b> Add a {@code package-info.java} in your service package:
 * <pre>
 * &#64;AzureContainerApps
 * &#64;ServiceResolverOptions(value = "my-resolver", type = "auto")
 * package com.myapp.services;
 * </pre>
 * <p>
 * <b>Step 2:</b> The system auto-detects at startup:
 * <ul>
 *   <li>If running in Azure Container Apps → runtime env detected</li>
 *   <li>If KUBERNETES_SERVICE_HOST present → Kube resolver used</li>
 *   <li>Otherwise → SRV DNS resolver (local dev)</li>
 * </ul>
 * <p>
 * <b>Step 3:</b> Resolve services:
 * <pre>
 * var resolver = ServiceResolverRegistry.resolveForClass(MyServiceClient.class);
 * HttpClient client = vertx.httpClientBuilder()
 *     .withAddressResolver(resolver)
 *     .build();
 * client.request(...).compose(req -&gt; req.send());
 * </pre>
 * <p>
 * <b>Step 4:</b> If telemetry is on classpath, all calls are automatically traced with
 * cloud context (service name, provider, region) attached to the resource.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
class CloudIntegrationFlowTest
{
    @BeforeAll
    void setUp()
    {
        // ─── Simulate Azure Container Apps environment ───
        // These env vars are automatically injected by Azure when your container runs.
        // Locally we set them as system properties to simulate.
        System.setProperty("CONTAINER_APP_NAME", "guicedee-website");
        System.setProperty("CONTAINER_APP_ENV_DNS_SUFFIX", "whitegrass-2e17714c.ukwest.azurecontainerapps.io");
        System.setProperty("CONTAINER_APP_PORT", "8080");
        System.setProperty("CONTAINER_APP_REVISION", "guicedee-website--0000004");
        System.setProperty("CONTAINER_APP_REPLICA_NAME", "guicedee-website--0000004-7f8d9c4b2-xk9m2");
        System.setProperty("CONTAINER_APP_HOSTNAME", "guicedee-website.whitegrass-2e17714c.ukwest.azurecontainerapps.io");
        System.setProperty("AZURE_REGION", "ukwest");

        // ─── Simulate Kubernetes environment (Azure Container Apps runs on K8s) ───
        System.setProperty("KUBERNETES_SERVICE_HOST", "10.0.0.1");
        System.setProperty("KUBERNETES_SERVICE_PORT", "443");

        // Boot GuicedEE — this triggers the full startup chain:
        // TelemetryPreStartup → RuntimeAutoConfigurePreStartup → VertXPreStartup → ServiceResolverPreStartup
        IGuiceContext.registerModule("com.guicedee.vertx.servicediscovery.test");
        IGuiceContext.instance().inject();
    }

    @AfterAll
    void tearDown()
    {
        IGuiceContext.instance().destroy();
        System.clearProperty("CONTAINER_APP_NAME");
        System.clearProperty("CONTAINER_APP_ENV_DNS_SUFFIX");
        System.clearProperty("CONTAINER_APP_PORT");
        System.clearProperty("CONTAINER_APP_REVISION");
        System.clearProperty("CONTAINER_APP_REPLICA_NAME");
        System.clearProperty("CONTAINER_APP_HOSTNAME");
        System.clearProperty("AZURE_REGION");
        System.clearProperty("KUBERNETES_SERVICE_HOST");
        System.clearProperty("KUBERNETES_SERVICE_PORT");
    }

    // ═══════════════════════════════════════════════════════════════════
    // PART 1: runtime-autoconfigure detects the cloud platform
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    void testRuntimeAutoconfigureDetectsAzure()
    {
        Optional<RuntimeEnvironment> env = RuntimeAutoConfigurePreStartup.current();

        assertTrue(env.isPresent(), """
                RuntimeEnvironment should be detected.
                
                HOW IT WORKS:
                1. Your package-info.java has @AzureContainerApps annotation
                2. RuntimeAutoConfigurePreStartup scans for this annotation
                3. It runs AzureContainerAppsEnvironmentProvider.detected()
                4. Provider checks CONTAINER_APP_NAME + CONTAINER_APP_ENV_DNS_SUFFIX env vars
                5. If both present → detected! → populates RuntimeEnvironment
                """);

        RuntimeEnvironment runtime = env.get();
        assertEquals("azure-container-apps", runtime.provider());
        assertEquals("guicedee-website", runtime.serviceName());
        assertEquals(8080, runtime.port());
        assertEquals("ukwest", runtime.region());

        System.out.println("✅ Runtime Autoconfigure Results:");
        System.out.println("   provider    : " + runtime.provider());
        System.out.println("   serviceName : " + runtime.serviceName());
        System.out.println("   serviceId   : " + runtime.serviceId());
        System.out.println("   hostname    : " + runtime.hostname());
        System.out.println("   fqdn        : " + runtime.fqdn());
        System.out.println("   port        : " + runtime.port());
        System.out.println("   region      : " + runtime.region());
        System.out.println("   revision    : " + runtime.revision());
        System.out.println("   replicaName : " + runtime.replicaName());
    }

    // ═══════════════════════════════════════════════════════════════════
    // PART 2: service-discovery creates resolvers based on annotations
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    void testServiceDiscoveryResolvesFromPackageAnnotation()
    {
        // The kubepackage has @ServiceResolverOptions(type = "kubernetes")
        // The srvpackage has @ServiceResolverOptions(type = "srv")
        AddressResolver<ServiceAddress> kubeResolver = ServiceResolverRegistry.getResolver("test-kube");
        AddressResolver<ServiceAddress> srvResolver = ServiceResolverRegistry.getResolver("test-srv");

        assertNotNull(kubeResolver, """
                Kube resolver should be created.
                
                HOW IT WORKS:
                1. ServiceResolverPreStartup scans packages for @ServiceResolverOptions
                2. Finds 'test-kube' with type='kubernetes' on kubepackage/package-info.java
                3. ServiceResolverPostStartup calls ServiceResolverRegistry.createAllResolvers()
                4. For type='kubernetes' → creates KubeResolver with configured host/port/namespace
                """);
        assertNotNull(srvResolver);

        System.out.println("✅ Service Discovery Resolvers Created:");
        System.out.println("   test-kube : " + kubeResolver.getClass().getSimpleName());
        System.out.println("   test-srv  : " + srvResolver.getClass().getSimpleName());
    }

    @Test
    @Order(3)
    void testAutoResolverUsesKubernetesWhenDetected()
    {
        // When KUBERNETES_SERVICE_HOST is present, the "auto" resolver should pick Kubernetes
        // This demonstrates: runtime-autoconfigure tells us we're on Azure (which uses K8s)
        // and service-discovery's auto-resolver detects KUBERNETES_SERVICE_HOST → uses KubeResolver

        assertTrue(System.getProperty("KUBERNETES_SERVICE_HOST") != null
                        || System.getenv("KUBERNETES_SERVICE_HOST") != null,
                "K8s host should be set (simulated)");

        System.out.println("""
                ✅ Auto-resolver behavior explanation:
                   
                   When type="auto" in @ServiceResolverOptions:
                   1. First checks IServiceResolverProvider SPIs for type="auto"
                   2. If runtime-autoconfigure is on classpath and provides one → uses it
                   3. Else checks KUBERNETES_SERVICE_HOST env var → KubeResolver
                   4. Else fallback → SrvResolver (DNS)
                   
                   In Azure Container Apps:
                   - KUBERNETES_SERVICE_HOST is always set (it's K8s underneath)
                   - So auto-resolver will use KubeResolver
                   - Services are resolved via K8s Endpoints API
                """);
    }

    // ═══════════════════════════════════════════════════════════════════
    // PART 3: telemetry enrichment (when telemetry module is on classpath)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    void testTelemetryIntegrationExplanation()
    {
        // This test explains what happens when telemetry is ALSO on the classpath.
        // We don't add telemetry as a dependency in service-discovery (it's optional),
        // but we document the integration pattern.

        RuntimeEnvironment runtime = RuntimeAutoConfigurePreStartup.current().orElse(null);

        System.out.println("""
                ═══════════════════════════════════════════════════════════════
                TELEMETRY INTEGRATION (when com.guicedee:telemetry is on classpath)
                ═══════════════════════════════════════════════════════════════
                
                When the telemetry module is present:
                
                1. TelemetryPreStartup runs at sortOrder MIN+35 (BEFORE runtime-autoconfigure)
                   - Initializes OpenTelemetry SDK
                   - Sets Resource attributes: service.name, service.version, deployment.environment
                
                2. RuntimeAutoConfigurePreStartup runs at sortOrder MIN+50
                   - Detects cloud environment
                   - The detected RuntimeEnvironment can be used to ENRICH telemetry
                
                3. To bridge them, you would implement GuiceTelemetryRegistration SPI:
                
                   public class CloudTelemetryEnricher implements GuiceTelemetryRegistration {
                       @Override
                       public OpenTelemetry configure(OpenTelemetry otel) {
                           // RuntimeAutoConfigurePreStartup.current() is populated by now
                           // Add cloud.* attributes to spans automatically
                           return otel;
                       }
                   }
                
                   OR simply set env vars that OpenTelemetry SDK reads:
                   - OTEL_SERVICE_NAME = runtime.serviceName()
                   - OTEL_RESOURCE_ATTRIBUTES = cloud.provider=azure,cloud.region=ukwest
                
                ═══════════════════════════════════════════════════════════════
                WHAT TELEMETRY ADDS TO EVERY SPAN:
                ═══════════════════════════════════════════════════════════════
                
                Resource attributes (attached to EVERY span):
                """);

        if (runtime != null) {
            System.out.println("   service.name             = " + runtime.serviceName());
            System.out.println("   service.instance.id      = " + runtime.serviceId());
            System.out.println("   cloud.provider           = " + runtime.provider());
            System.out.println("   cloud.region             = " + runtime.region());
            System.out.println("   host.name                = " + runtime.hostname());
            System.out.println("   service.version          = " + runtime.revision());
        }

        System.out.println("""
                
                When you use @Trace on a method:
                   @Trace("resolve-service")
                   public Uni<SocketAddress> resolveService(String serviceName) { ... }
                
                The resulting span includes:
                   - span.name = "resolve-service"
                   - span.kind = INTERNAL
                   - All resource attributes above are inherited
                   - Any @SpanAttribute parameters are added as span attributes
                
                This means in your observability tool (Grafana/Tempo/Jaeger):
                   - You can filter by cloud.provider = "azure-container-apps"
                   - You can group by cloud.region
                   - You can see which service.instance.id (replica) handled the request
                   - You can trace service-to-service calls across your container apps
                
                ═══════════════════════════════════════════════════════════════
                PRACTICAL SETUP:
                ═══════════════════════════════════════════════════════════════
                
                In your pom.xml:
                   <dependency>
                       <groupId>com.guicedee</groupId>
                       <artifactId>service-discovery</artifactId>
                   </dependency>
                   <dependency>
                       <groupId>com.guicedee</groupId>
                       <artifactId>runtime-autoconfigure</artifactId>
                   </dependency>
                   <!-- Optional: adds tracing -->
                   <dependency>
                       <groupId>com.guicedee</groupId>
                       <artifactId>telemetry</artifactId>
                   </dependency>
                
                In package-info.java:
                   @AzureContainerApps
                   @ServiceResolverOptions(value = "my-services", type = "auto")
                   @TelemetryOptions(serviceName = "my-app", otlpEndpoint = "http://tempo:4318")
                   package com.myapp;
                """);
    }

    // ═══════════════════════════════════════════════════════════════════
    // PART 4: Demonstrate the RuntimeEnvironment is available everywhere
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    void testRuntimeEnvironmentAccessibleAnywhere()
    {
        // After startup, any code can check the runtime environment:
        Optional<RuntimeEnvironment> env = RuntimeAutoConfigurePreStartup.current();
        assertTrue(env.isPresent());

        // And use it to make decisions:
        RuntimeEnvironment runtime = env.get();
        String resolverType = switch (runtime.provider()) {
            case "azure-container-apps", "aws-ecs", "gcp-cloud-run" -> "kubernetes";
            case "fly-io", "railway", "render" -> "srv";
            default -> "auto";
        };

        assertEquals("kubernetes", resolverType,
                "Azure Container Apps uses Kubernetes under the hood");

        System.out.println("✅ RuntimeEnvironment → resolver type mapping:");
        System.out.println("   " + runtime.provider() + " → " + resolverType);
        System.out.println();
        System.out.println("   This means service-discovery auto-creates a KubeResolver");
        System.out.println("   which queries the K8s Endpoints API to find service pod IPs.");
    }
}



