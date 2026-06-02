open module com.guicedee.vertx.servicediscovery.test {
    requires transitive com.guicedee.vertx.servicediscovery;
    requires com.guicedee.runtime.autoconfigure;
    requires com.guicedee.guicedinjection;
    requires com.google.guice;
    requires io.vertx.core;
    requires io.vertx.serviceresolver;

    requires org.junit.jupiter;
}
