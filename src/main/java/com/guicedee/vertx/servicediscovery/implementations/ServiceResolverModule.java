package com.guicedee.vertx.servicediscovery.implementations;

import com.google.inject.AbstractModule;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;
import com.guicedee.client.services.lifecycle.IGuiceModule;
import com.guicedee.vertx.servicediscovery.ServiceResolverRegistry;
import io.vertx.core.net.AddressResolver;
import io.vertx.serviceresolver.ServiceAddress;
import lombok.extern.log4j.Log4j2;

/**
 * Guice module that binds {@link AddressResolver} instances (named by resolver config name)
 * so they can be injected via {@code @Inject @Named("resolverName")}.
 */
@Log4j2
public class ServiceResolverModule extends AbstractModule implements IGuiceModule<ServiceResolverModule>
{
    @Override
    protected void configure()
    {
        var resolvers = ServiceResolverRegistry.getResolvers();
        for (var entry : resolvers.entrySet())
        {
            String name = entry.getKey();
            bind(new TypeLiteral<AddressResolver<ServiceAddress>>() {})
                    .annotatedWith(Names.named(name))
                    .toProvider(() -> ServiceResolverRegistry.getResolver(name));
            log.debug("📋 Bound AddressResolver<ServiceAddress> @Named(\"{}\")", name);
        }

        // Bind default (unnamed) if there's exactly one or a "default" named resolver
        if (resolvers.containsKey("default"))
        {
            bind(new TypeLiteral<AddressResolver<ServiceAddress>>() {})
                    .toProvider(() -> ServiceResolverRegistry.getResolver("default"));
        }
        else if (resolvers.size() == 1)
        {
            String onlyName = resolvers.keySet().iterator().next();
            bind(new TypeLiteral<AddressResolver<ServiceAddress>>() {})
                    .toProvider(() -> ServiceResolverRegistry.getResolver(onlyName));
        }
    }
}

