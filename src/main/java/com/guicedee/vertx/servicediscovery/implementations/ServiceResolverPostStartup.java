package com.guicedee.vertx.servicediscovery.implementations;

import com.guicedee.client.services.lifecycle.IGuicePostStartup;
import com.guicedee.vertx.servicediscovery.ServiceResolverRegistry;
import io.smallrye.mutiny.Uni;
import lombok.extern.log4j.Log4j2;

import java.util.List;

/**
 * Post-startup hook that eagerly creates all discovered service resolvers.
 * <p>
 * Runs after the injector is built but before the application is considered ready.
 */
@Log4j2
public class ServiceResolverPostStartup implements IGuicePostStartup<ServiceResolverPostStartup>
{
    @Override
    public List<Uni<Boolean>> postLoad()
    {
        return List.of(Uni.createFrom().item(() -> {
            log.info("🔍 Initializing service resolvers");
            ServiceResolverRegistry.createAllResolvers();
            log.info("🎉 Service resolver initialization completed ({} resolver(s))",
                    ServiceResolverRegistry.getResolvers().size());
            return true;
        }));
    }

    @Override
    public Integer sortOrder()
    {
        return Integer.MIN_VALUE + 600;
    }
}

