package com.guicedee.vertx.servicediscovery;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares service resolver configuration for a package or class.
 * <p>
 * Place on {@code package-info.java} to define the resolver for all services
 * in that package subtree, or on a specific class for targeted resolver configuration.
 * <p>
 * All values support environment variable override using the pattern:
 * {@code SERVICE_RESOLVER_{NORMALIZED_NAME}_{PROPERTY}}
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PACKAGE, ElementType.TYPE})
public @interface ServiceResolverOptions
{
    /**
     * @return Logical name for this resolver configuration (used in env var lookups).
     */
    String value() default "default";

    /**
     * @return Resolver type: "auto", "kubernetes", "kube", "srv", "dns", "consul", or a custom SPI type.
     */
    String type() default "auto";

    /**
     * @return Consul agent host (used when type = "consul").
     */
    String consulHost() default "localhost";

    /**
     * @return Consul agent port (used when type = "consul").
     */
    int consulPort() default 8500;

    /**
     * @return Consul ACL token (used when type = "consul").
     */
    String consulToken() default "";

    /**
     * @return Consul datacenter (used when type = "consul").
     */
    String consulDatacenter() default "";

    /**
     * @return Whether to query only passing/healthy instances (used when type = "consul").
     */
    boolean consulPassingOnly() default true;

    /**
     * @return Kubernetes API server host.
     */
    String kubeHost() default "";

    /**
     * @return Kubernetes API server port.
     */
    int kubePort() default 443;

    /**
     * @return Kubernetes namespace to resolve services in.
     */
    String kubeNamespace() default "";

    /**
     * @return Whether to trust all SSL certificates when connecting to the Kubernetes API server.
     */
    boolean kubeTrustAll() default false;

    /**
     * @return Bearer token for Kubernetes API authentication.
     */
    String kubeBearerToken() default "";

    /**
     * @return DNS server host for SRV resolution.
     */
    String srvHost() default "";

    /**
     * @return DNS server port for SRV resolution.
     */
    int srvPort() default 53;

    /**
     * @return Load balancer strategy: "round_robin", "least_requests", "random".
     */
    String loadBalancer() default "round_robin";
}

