package com.miriyum.architecture;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;

final class SpringMvcRouteInventory {

    private static final Comparator<ControllerRoute> ROUTE_ORDER = Comparator
            .comparing((ControllerRoute route) -> route.route().path())
            .thenComparing(route -> route.route().method().name())
            .thenComparing(ControllerRoute::packageName);

    private SpringMvcRouteInventory() {
    }

    static Set<ControllerRoute> routes() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(MetadataReader metadataReader)
                            throws IOException {
                        return metadataReader.getAnnotationMetadata()
                                .hasAnnotation(RestController.class.getName());
                    }
                };
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<Class<?>> controllers = new LinkedHashSet<>();
        ClassLoader classLoader = SpringMvcRouteInventory.class.getClassLoader();
        scanner.findCandidateComponents("com.miriyum.domain").forEach(candidate -> {
            try {
                controllers.add(ClassUtils.forName(candidate.getBeanClassName(), classLoader));
            } catch (ClassNotFoundException exception) {
                throw new IllegalStateException("Controller class could not be loaded: "
                        + candidate.getBeanClassName(), exception);
            }
        });
        return routesFor(controllers.toArray(Class<?>[]::new));
    }

    static Set<ControllerRoute> routesFor(Class<?>... controllers) {
        MappingInspector inspector = new MappingInspector();
        Set<ControllerRoute> routes = new TreeSet<>(ROUTE_ORDER);

        for (Class<?> controller : controllers) {
            for (Method method : controller.getDeclaredMethods()) {
                RequestMappingInfo mapping = inspector.mappingFor(method, controller);
                if (mapping == null) {
                    continue;
                }
                Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();
                if (methods.isEmpty()) {
                    throw new IllegalStateException("Controller mapping must declare an explicit HTTP method: "
                            + controller.getName() + "#" + method.getName());
                }
                for (String path : mapping.getPatternValues()) {
                    for (RequestMethod requestMethod : methods) {
                        routes.add(new ControllerRoute(
                                controller.getPackageName(),
                                new ApiRoute(requestMethod, path)));
                    }
                }
            }
        }
        return Set.copyOf(routes);
    }

    private static final class MappingInspector extends RequestMappingHandlerMapping {

        private MappingInspector() {
            setPatternParser(new PathPatternParser());
        }

        private RequestMappingInfo mappingFor(Method method, Class<?> controllerType) {
            return getMappingForMethod(method, controllerType);
        }
    }
}

record ControllerRoute(String packageName, ApiRoute route) {
}
