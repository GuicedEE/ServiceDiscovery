package com.guicedee.vertx.servicediscovery.implementations;

import com.guicedee.client.IGuiceContext;
import com.guicedee.client.services.lifecycle.IGuicePreStartup;
import com.guicedee.vertx.servicediscovery.ServiceResolverOptions;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.PackageInfo;
import io.github.classgraph.ScanResult;
import io.vertx.core.Future;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pre-startup scanner that discovers {@link ServiceResolverOptions} annotations
 * on packages and classes, wraps them with environment variable resolution,
 * and registers resolver configurations for binding.
 */
@Log4j2
public class ServiceResolverPreStartup implements IGuicePreStartup<ServiceResolverPreStartup>
{
    /**
     * Package name → resolved options for that package's resolver.
     */
    @Getter
    private static final Map<String, ServiceResolverOptions> packageResolverOptions = new ConcurrentHashMap<>();

    /**
     * Resolver name → resolved options.
     */
    @Getter
    private static final Map<String, ServiceResolverOptions> namedResolverOptions = new ConcurrentHashMap<>();

    @Override
    public List<Future<Boolean>> onStartup()
    {
        ScanResult scanResult = IGuiceContext.instance().getScanResult();
        scanPackageAnnotations(scanResult);
        scanClassAnnotations(scanResult);
        log.info("🔍 Discovered {} service resolver configuration(s)", namedResolverOptions.size());
        return List.of(Future.succeededFuture(true));
    }

    private void scanPackageAnnotations(ScanResult scanResult)
    {
        for (PackageInfo packageInfo : scanResult.getPackageInfo())
        {
            var annotationInfo = packageInfo.getAnnotationInfo(ServiceResolverOptions.class.getName());
            if (annotationInfo != null)
            {
                try
                {
                    // Load the package-info class to get the annotation
                    Class<?> packageInfoClass = Class.forName(packageInfo.getName() + ".package-info");
                    ServiceResolverOptions annotation = packageInfoClass.getAnnotation(ServiceResolverOptions.class);
                    if (annotation != null)
                    {
                        ServiceResolverOptions wrapped = wrapOptions(annotation);
                        String resolverName = wrapped.value();
                        packageResolverOptions.put(packageInfo.getName(), wrapped);
                        namedResolverOptions.put(resolverName, wrapped);
                        log.debug("📋 Found @ServiceResolverOptions on package '{}' (name='{}')",
                                packageInfo.getName(), resolverName);
                    }
                }
                catch (ClassNotFoundException e)
                {
                    log.debug("Could not load package-info for {}: {}", packageInfo.getName(), e.getMessage());
                }
            }
        }
    }

    private void scanClassAnnotations(ScanResult scanResult)
    {
        var classesWithAnnotation = scanResult.getClassesWithAnnotation(ServiceResolverOptions.class);
        for (ClassInfo classInfo : classesWithAnnotation)
        {
            if (classInfo.getName().endsWith(".package-info")) continue; // already handled
            try
            {
                Class<?> clazz = classInfo.loadClass();
                ServiceResolverOptions annotation = clazz.getAnnotation(ServiceResolverOptions.class);
                if (annotation != null)
                {
                    ServiceResolverOptions wrapped = wrapOptions(annotation);
                    String resolverName = wrapped.value();
                    packageResolverOptions.put(classInfo.getPackageName(), wrapped);
                    namedResolverOptions.put(resolverName, wrapped);
                    log.debug("📋 Found @ServiceResolverOptions on class '{}' (name='{}')",
                            classInfo.getName(), resolverName);
                }
            }
            catch (Exception e)
            {
                log.error("Error processing @ServiceResolverOptions on class {}", classInfo.getName(), e);
            }
        }
    }

    /**
     * Wraps a source annotation with environment variable resolution.
     */
    static ServiceResolverOptions wrapOptions(ServiceResolverOptions source)
    {
        String rawName = source.value();
        return new ServiceResolverOptions()
        {
            @Override
            public Class<? extends Annotation> annotationType() { return ServiceResolverOptions.class; }

            @Override
            public String value() { return envForName(rawName, "NAME", source.value()); }

            @Override
            public String type() { return envForName(rawName, "TYPE", source.type()); }

            @Override
            public String kubeHost() { return envForName(rawName, "KUBE_HOST", source.kubeHost()); }

            @Override
            public int kubePort() { return Integer.parseInt(envForName(rawName, "KUBE_PORT", String.valueOf(source.kubePort()))); }

            @Override
            public String kubeNamespace() { return envForName(rawName, "KUBE_NAMESPACE", source.kubeNamespace()); }

            @Override
            public String srvHost() { return envForName(rawName, "SRV_HOST", source.srvHost()); }

            @Override
            public int srvPort() { return Integer.parseInt(envForName(rawName, "SRV_PORT", String.valueOf(source.srvPort()))); }

            @Override
            public String loadBalancer() { return envForName(rawName, "LOAD_BALANCER", source.loadBalancer()); }

            @Override
            public String consulHost() { return envForName(rawName, "CONSUL_HOST", source.consulHost()); }

            @Override
            public int consulPort() { return Integer.parseInt(envForName(rawName, "CONSUL_PORT", String.valueOf(source.consulPort()))); }

            @Override
            public String consulToken() { return envForName(rawName, "CONSUL_TOKEN", source.consulToken()); }

            @Override
            public String consulDatacenter() { return envForName(rawName, "CONSUL_DATACENTER", source.consulDatacenter()); }

            @Override
            public boolean consulPassingOnly() { return Boolean.parseBoolean(envForName(rawName, "CONSUL_PASSING_ONLY", String.valueOf(source.consulPassingOnly()))); }

            @Override
            public boolean kubeTrustAll() { return Boolean.parseBoolean(envForName(rawName, "KUBE_TRUST_ALL", String.valueOf(source.kubeTrustAll()))); }

            @Override
            public String kubeBearerToken() { return envForName(rawName, "KUBE_BEARER_TOKEN", source.kubeBearerToken()); }
        };
    }

    /**
     * Resolves an environment variable scoped by resolver name.
     * <p>
     * Lookup order:
     * <ol>
     *   <li>{@code SERVICE_RESOLVER_{NORMALIZED_NAME}_{PROPERTY}} — name-specific override</li>
     *   <li>{@code SERVICE_RESOLVER_{PROPERTY}} — global fallback</li>
     *   <li>The supplied {@code defaultValue}</li>
     * </ol>
     */
    public static String envForName(String name, String property, String defaultValue)
    {
        String normalizedName = name.toUpperCase().replace('-', '_').replace('.', '_');
        // Try name-scoped: SERVICE_RESOLVER_{NAME}_{PROPERTY}
        String scopedKey = "SERVICE_RESOLVER_" + normalizedName + "_" + property;
        String scopedValue = System.getProperty(scopedKey);
        if (scopedValue == null) scopedValue = System.getenv(scopedKey);
        if (scopedValue == null) scopedValue = System.getenv(scopedKey.toUpperCase());
        if (scopedValue != null && !scopedValue.isBlank())
        {
            return scopedValue;
        }
        // Try global: SERVICE_RESOLVER_{PROPERTY}
        String globalKey = "SERVICE_RESOLVER_" + property;
        String globalValue = System.getProperty(globalKey);
        if (globalValue == null) globalValue = System.getenv(globalKey);
        if (globalValue == null) globalValue = System.getenv(globalKey.toUpperCase());
        if (globalValue != null && !globalValue.isBlank())
        {
            return globalValue;
        }
        return defaultValue;
    }

    @Override
    public Integer sortOrder()
    {
        return Integer.MIN_VALUE + 90;
    }
}


