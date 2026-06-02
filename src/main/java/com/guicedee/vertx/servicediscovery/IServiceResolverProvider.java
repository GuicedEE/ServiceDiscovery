package com.guicedee.vertx.servicediscovery;

import com.guicedee.client.services.IDefaultService;
import io.vertx.core.net.AddressResolver;
import io.vertx.serviceresolver.ServiceAddress;

/**
 * SPI for providing custom {@link AddressResolver} implementations.
 * <p>
 * Modules can implement this interface to supply resolvers for custom types
 * (e.g. "consul", "eureka", etc.) that the core service-discovery module
 * does not natively support.
 * <p>
 * Implementations are discovered via {@link java.util.ServiceLoader} and matched
 * by the {@link #type()} they support.
 */
public interface IServiceResolverProvider<J extends IServiceResolverProvider<J>> extends IDefaultService<J>
{
    /**
     * @return The resolver type string this provider handles (e.g. "consul").
     */
    String type();

    /**
     * Creates an address resolver from the given options.
     *
     * @param name    The logical resolver name.
     * @param options The resolved annotation options.
     * @return A new address resolver, or null if creation fails.
     */
    AddressResolver<ServiceAddress> create(String name, ServiceResolverOptions options);
}

