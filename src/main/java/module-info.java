import com.guicedee.client.services.lifecycle.IGuiceModule;
import com.guicedee.client.services.lifecycle.IGuicePostStartup;
import com.guicedee.client.services.lifecycle.IGuicePreStartup;
import com.guicedee.vertx.servicediscovery.implementations.ServiceResolverModule;
import com.guicedee.vertx.servicediscovery.implementations.ServiceResolverPostStartup;
import com.guicedee.vertx.servicediscovery.implementations.ServiceResolverPreStartup;

module com.guicedee.vertx.servicediscovery {

    exports com.guicedee.vertx.servicediscovery;

    requires transitive com.guicedee.vertx;
    requires transitive io.vertx.core;
    requires transitive io.vertx.serviceresolver;
    requires com.google.guice;
    requires io.github.classgraph;
    requires static lombok;

    provides IGuicePreStartup with ServiceResolverPreStartup;
    provides IGuicePostStartup with ServiceResolverPostStartup;
    provides IGuiceModule with ServiceResolverModule;

    uses com.guicedee.vertx.servicediscovery.IServiceResolverProvider;

    opens com.guicedee.vertx.servicediscovery to com.google.guice;
    opens com.guicedee.vertx.servicediscovery.implementations to com.google.guice;

    //tests
    exports com.guicedee.vertx.servicediscovery.implementations to com.guicedee.vertx.servicediscovery.test;
}
