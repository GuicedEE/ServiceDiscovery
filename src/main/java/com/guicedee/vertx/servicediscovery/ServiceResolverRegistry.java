package com.guicedee.vertx.servicediscovery;

import com.guicedee.client.Environment;
import com.guicedee.client.IGuiceContext;
import com.guicedee.vertx.servicediscovery.implementations.ServiceResolverPreStartup;
import io.vertx.core.net.AddressResolver;
import io.vertx.core.net.SocketAddress;
import io.vertx.serviceresolver.ServiceAddress;
import io.vertx.serviceresolver.kube.KubeResolver;
import io.vertx.serviceresolver.kube.KubeResolverOptions;
import io.vertx.serviceresolver.srv.SrvResolver;
import io.vertx.serviceresolver.srv.SrvResolverOptions;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry that creates and caches {@link AddressResolver} instances based on
 * discovered {@link ServiceResolverOptions} configurations.
 * <p>
 * Resolvers are created lazily on first access or eagerly during post-startup.
 */
@Log4j2
public class ServiceResolverRegistry {
    @Getter
    private static final Map<String, AddressResolver<ServiceAddress>> resolvers = new ConcurrentHashMap<>();

    private ServiceResolverRegistry() {
    }

    /**
     * Gets or creates a resolver by name.
     *
     * @param name The resolver name (from {@link ServiceResolverOptions#value()}).
     * @return The address resolver, or null if no configuration exists for that name.
     */
    public static AddressResolver<ServiceAddress> getResolver(String name) {
        return resolvers.computeIfAbsent(name, ServiceResolverRegistry::createResolver);
    }

    /**
     * Creates all resolvers from discovered configurations.
     */
    public static void createAllResolvers() {
        for (var entry : ServiceResolverPreStartup.getNamedResolverOptions().entrySet()) {
            resolvers.computeIfAbsent(entry.getKey(), ServiceResolverRegistry::createResolver);
        }
    }

    private static AddressResolver<ServiceAddress> createResolver(String name) {
        ServiceResolverOptions options = ServiceResolverPreStartup.getNamedResolverOptions().get(name);
        if (options == null) {
            log.warn("⚠️ No @ServiceResolverOptions configuration found for resolver '{}'", name);
            return null;
        }

        String type = options.type().toLowerCase().trim();
        return switch (type) {
            case "kubernetes", "kube" -> createKubeResolver(name, options);
            case "srv", "dns" -> createSrvResolver(name, options);
            case "auto" -> createAutoResolver(name, options);
            default -> createFromProvider(name, type, options);
        };
    }

    private static AddressResolver<ServiceAddress> createKubeResolver(String name, ServiceResolverOptions options) {
        log.info("🚀 Creating Kubernetes service resolver '{}'", name);
        KubeResolverOptions kubeOptions = new KubeResolverOptions();

        String host = options.kubeHost();
        if (!host.isEmpty()) {
            kubeOptions.setServer(SocketAddress.inetSocketAddress(options.kubePort(), host));
            log.debug("📋 Kubernetes API server for '{}': {}:{}", name, host, options.kubePort());
        }

        String namespace = options.kubeNamespace();
        if (!namespace.isEmpty()) {
            kubeOptions.setNamespace(namespace);
        }

        // Trust all certificates if configured (useful for local/dev clusters)
        boolean trustAll = Boolean.parseBoolean(
                ServiceResolverPreStartup.envForName(name, "KUBE_TRUST_ALL", String.valueOf(options.kubeTrustAll()))
        );
        if (trustAll) {
            var httpOpts = kubeOptions.getHttpClientOptions();
            if (httpOpts == null) {
                httpOpts = new io.vertx.core.http.HttpClientOptions();
            }
            httpOpts.setTrustAll(true);
            httpOpts.setSsl(true);
            kubeOptions.setHttpClientOptions(httpOpts);

            var wsOpts = kubeOptions.getWebSocketClientOptions();
            if (wsOpts == null) {
                wsOpts = new io.vertx.core.http.WebSocketClientOptions();
            }
            wsOpts.setTrustAll(true);
            wsOpts.setSsl(true);
            kubeOptions.setWebSocketClientOptions(wsOpts);
            log.info("🔓 Trust-all SSL enabled for Kubernetes resolver '{}'", name);
        }

        // Bearer token
        String bearerToken = ServiceResolverPreStartup.envForName(name, "KUBE_BEARER_TOKEN", options.kubeBearerToken());
        if (bearerToken != null && !bearerToken.isEmpty()) {
            kubeOptions.setBearerToken(bearerToken);
            log.debug("🔑 Bearer token configured for Kubernetes resolver '{}'", name);
        }

        AddressResolver<ServiceAddress> resolver = KubeResolver.create(kubeOptions);
        log.info("✅ Kubernetes service resolver '{}' created", name);
        return resolver;
    }

    @SuppressWarnings("unchecked")
    private static AddressResolver<ServiceAddress> createSrvResolver(String name, ServiceResolverOptions options) {
        log.info("🚀 Creating SRV DNS service resolver '{}'", name);
        SrvResolverOptions srvOptions = new SrvResolverOptions();

        String host = options.srvHost();
        if (!host.isEmpty()) {
            srvOptions.setServer(SocketAddress.inetSocketAddress(options.srvPort(), host));
            log.debug("📋 DNS server for '{}': {}:{}", name, host, options.srvPort());
        }

        AddressResolver resolver = SrvResolver.create(srvOptions);
        log.info("✅ SRV DNS service resolver '{}' created", name);
        return (AddressResolver<ServiceAddress>) resolver;
    }

    /**
     * Resolves which resolver to use for a given class based on its package hierarchy.
     *
     * @param clazz The class requesting a resolver.
     * @return The resolver, or null if none configured.
     */
    public static AddressResolver<ServiceAddress> resolveForClass(Class<?> clazz) {
        String packageName = clazz.getPackageName();
        while (!packageName.isEmpty()) {
            ServiceResolverOptions options = ServiceResolverPreStartup.getPackageResolverOptions().get(packageName);
            if (options != null) {
                return getResolver(options.value());
            }
            int lastDot = packageName.lastIndexOf('.');
            if (lastDot < 0) break;
            packageName = packageName.substring(0, lastDot);
        }
        // Fallback to default
        return getResolver("default");
    }

    /**
     * Auto-resolves the best resolver type based on available SPI providers and environment.
     * <p>
     * Resolution order:
     * <ol>
     *   <li>First {@link IServiceResolverProvider} that reports {@code type() = "auto"}</li>
     *   <li>Kubernetes resolver (if KUBERNETES_SERVICE_HOST present)</li>
     *   <li>SRV DNS resolver (fallback)</li>
     * </ol>
     * <p>
     * If {@code runtime-autoconfigure} is on the classpath, it can provide an
     * {@link IServiceResolverProvider} with type "auto" that bridges cloud detection.
     */
    private static AddressResolver<ServiceAddress> createAutoResolver(String name, ServiceResolverOptions options) {
        log.info("🔍 Auto-resolving service resolver type for '{}'", name);

        // Check if any SPI provider handles "auto" type
        var providers = IGuiceContext.loaderToSet(ServiceLoader.load(IServiceResolverProvider.class));
        for (var provider : providers) {
            IServiceResolverProvider<?> resolverProvider = (IServiceResolverProvider<?>) provider;
            if ("auto".equals(resolverProvider.type())) {
                var resolver = resolverProvider.create(name, options);
                if (resolver != null) {
                    log.info("🔌 Auto resolver '{}' provided by: {}", name, resolverProvider.getClass().getName());
                    return resolver;
                }
            }
        }

        // Fallback: check environment for Kubernetes
        String kubeHost = Environment.getSystemPropertyOrEnvironment("KUBERNETES_SERVICE_HOST", "");
        if (!kubeHost.isEmpty()) {
            log.info("☁️  Auto: KUBERNETES_SERVICE_HOST detected, using Kubernetes resolver for '{}'", name);
            return createKubeResolver(name, options);
        }

        // Final fallback: SRV DNS
        log.info("☁️  Auto: no specific platform detected, using SRV DNS resolver for '{}'", name);
        return createSrvResolver(name, options);
    }

    /**
     * Delegates resolver creation to SPI providers for types not natively supported.
     */
    @SuppressWarnings("unchecked")
    private static AddressResolver<ServiceAddress> createFromProvider(String name, String type, ServiceResolverOptions options) {
        var providers = IGuiceContext.loaderToSet(ServiceLoader.load(IServiceResolverProvider.class));
        for (var provider : providers) {
            IServiceResolverProvider<?> resolverProvider = (IServiceResolverProvider<?>) provider;
            if (type.equals(resolverProvider.type())) {
                log.info("🔌 Delegating resolver '{}' (type='{}') to provider: {}",
                        name, type, resolverProvider.getClass().getName());
                return resolverProvider.create(name, options);
            }
        }
        log.warn("⚠️ No resolver provider found for type '{}' (resolver '{}'). " +
                "Supported built-in types: kubernetes, srv. " +
                "Provide an IServiceResolverProvider implementation for custom types.", type, name);
        return null;
    }
}

