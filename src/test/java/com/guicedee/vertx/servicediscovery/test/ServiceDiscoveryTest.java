package com.guicedee.vertx.servicediscovery.test;

import com.guicedee.client.IGuiceContext;
import com.guicedee.vertx.servicediscovery.implementations.ServiceResolverPreStartup;
import com.guicedee.vertx.servicediscovery.ServiceResolverRegistry;
import com.guicedee.vertx.servicediscovery.test.kubepackage.KubeServiceClient;
import com.guicedee.vertx.servicediscovery.test.srvpackage.SrvServiceClient;
import io.vertx.core.net.AddressResolver;
import io.vertx.serviceresolver.ServiceAddress;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Service Discovery module.
 * <p>
 * Verifies:
 * - Annotation scanning discovers package-level @ServiceResolverOptions
 * - Different packages get different resolver types
 * - Environment variable overrides work
 * - Resolvers are created and accessible by name
 * - Package-based resolver lookup works
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ServiceDiscoveryTest
{
    @BeforeAll
    void setUp()
    {
        IGuiceContext.registerModule("com.guicedee.vertx.servicediscovery.test");
        IGuiceContext.instance().inject();
    }

    @AfterAll
    void tearDown()
    {
        IGuiceContext.instance().destroy();
    }

    @Test
    @Order(1)
    void testAnnotationScanningDiscoversSrvPackage()
    {
        var options = ServiceResolverPreStartup.getNamedResolverOptions();
        assertTrue(options.containsKey("test-srv"),
                "Should discover 'test-srv' resolver from srvpackage package-info");
        assertEquals("srv", options.get("test-srv").type());
        System.out.println("✅ SRV resolver options discovered from package annotation");
    }

    @Test
    @Order(2)
    void testAnnotationScanningDiscoversKubePackage()
    {
        var options = ServiceResolverPreStartup.getNamedResolverOptions();
        assertTrue(options.containsKey("test-kube"),
                "Should discover 'test-kube' resolver from kubepackage package-info");
        assertEquals("kubernetes", options.get("test-kube").type());
        assertEquals("127.0.0.1", options.get("test-kube").kubeHost());
        assertEquals(16443, options.get("test-kube").kubePort());
        assertEquals("test-ns", options.get("test-kube").kubeNamespace());
        System.out.println("✅ Kubernetes resolver options discovered from package annotation");
    }

    @Test
    @Order(3)
    void testSrvResolverCreatedSuccessfully()
    {
        AddressResolver<ServiceAddress> resolver = ServiceResolverRegistry.getResolver("test-srv");
        assertNotNull(resolver, "SRV resolver should be created for 'test-srv'");
        System.out.println("✅ SRV resolver created successfully");
    }

    @Test
    @Order(4)
    void testKubeResolverCreatedSuccessfully()
    {
        AddressResolver<ServiceAddress> resolver = ServiceResolverRegistry.getResolver("test-kube");
        assertNotNull(resolver, "Kubernetes resolver should be created for 'test-kube'");
        System.out.println("✅ Kubernetes resolver created successfully");
    }

    @Test
    @Order(5)
    void testPackageBasedResolverLookupForSrvPackage()
    {
        AddressResolver<ServiceAddress> resolver = ServiceResolverRegistry.resolveForClass(SrvServiceClient.class);
        assertNotNull(resolver, "Should resolve SRV resolver for SrvServiceClient class");
        assertSame(ServiceResolverRegistry.getResolver("test-srv"), resolver,
                "Package-based lookup should return the same SRV resolver");
        System.out.println("✅ Package-based resolver lookup works for SRV package");
    }

    @Test
    @Order(6)
    void testPackageBasedResolverLookupForKubePackage()
    {
        AddressResolver<ServiceAddress> resolver = ServiceResolverRegistry.resolveForClass(KubeServiceClient.class);
        assertNotNull(resolver, "Should resolve Kube resolver for KubeServiceClient class");
        assertSame(ServiceResolverRegistry.getResolver("test-kube"), resolver,
                "Package-based lookup should return the same Kube resolver");
        System.out.println("✅ Package-based resolver lookup works for Kube package");
    }

    @Test
    @Order(7)
    void testEnvironmentVariableOverride()
    {
        System.setProperty("SERVICE_RESOLVER_TEST_SRV_SRV_PORT", "25353");
        try
        {
            String resolved = ServiceResolverPreStartup.envForName("test-srv", "SRV_PORT", "99");
            assertEquals("25353", resolved, "Should resolve to env override value");
            System.out.println("✅ Environment variable override works correctly");
        }
        finally
        {
            System.clearProperty("SERVICE_RESOLVER_TEST_SRV_SRV_PORT");
        }
    }

    @Test
    @Order(8)
    void testEnvironmentVariableGlobalFallback()
    {
        System.setProperty("SERVICE_RESOLVER_LOAD_BALANCER", "least_requests");
        try
        {
            String resolved = ServiceResolverPreStartup.envForName("unknown", "LOAD_BALANCER", "round_robin");
            assertEquals("least_requests", resolved, "Should fall back to global SERVICE_RESOLVER_ prefix");
            System.out.println("✅ Global environment variable fallback works");
        }
        finally
        {
            System.clearProperty("SERVICE_RESOLVER_LOAD_BALANCER");
        }
    }
}

