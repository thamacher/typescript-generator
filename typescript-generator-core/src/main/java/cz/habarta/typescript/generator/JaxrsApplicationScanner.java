
package cz.habarta.typescript.generator;

import cz.habarta.typescript.generator.parser.*;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;
import javax.ws.rs.*;
import javax.ws.rs.core.*;


public class JaxrsApplicationScanner {

    private final ClassLoader classLoader;
    private Set<String> excludes;
    private Queue<Class<?>> resourceQueue;
    private List<SourceType<Type>> discoveredTypes;

    public JaxrsApplicationScanner() {
        this (JaxrsApplicationScanner.class.getClassLoader());
    }

    public JaxrsApplicationScanner(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public List<SourceType<Type>> scanJaxrsApplication(String jaxrsApplicationClassName, List<String> excludedClassNames) {
        System.out.println("Scanning JAX-RS application: " + jaxrsApplicationClassName);
        final ClassLoader originalContextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(classLoader);
            final Class<?> jaxrsApplicationClass = classLoader.loadClass(jaxrsApplicationClassName);
            final Constructor<?> constructor = jaxrsApplicationClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            final Application application = (Application) constructor.newInstance();
            return scanJaxrsApplication(application, excludedClassNames);
        } catch (ReflectiveOperationException e) {
            final String url = "https://github.com/vojtechhabarta/typescript-generator/wiki/JAX-RS-Application";
            final String message = "Cannot load JAX-RS application. For more information see " + url + ".";
            System.out.println(message);
            throw new RuntimeException(message, e);
        } finally {
            Thread.currentThread().setContextClassLoader(originalContextClassLoader);
        }
    }

    List<SourceType<Type>> scanJaxrsApplication(Application application, List<String> excludedClassNames) {
        resourceQueue = new LinkedList<>();
        discoveredTypes = new ArrayList<>();
        excludes = new LinkedHashSet<>();
        excludes.addAll(getDefaultExcludedClassNames());
        if (excludedClassNames != null) {
            excludes.addAll(excludedClassNames);
        }
        final LinkedHashSet<Class<?>> scannedResources = new LinkedHashSet<>();
        final List<Class<?>> applicationClasses = new ArrayList<>(application.getClasses());
        Collections.sort(applicationClasses, new Comparator<Class<?>>() {
            @Override
            public int compare(Class<?> o1, Class<?> o2) {
                return o1.getName().compareToIgnoreCase(o2.getName());
            }
        });
        for (Class<?> applicationClass : applicationClasses) {
            if (applicationClass.isAnnotationPresent(Path.class)) {
                resourceQueue.add(applicationClass);
            }
        }
        Class<?> resourceClass;
        while ((resourceClass = resourceQueue.poll()) != null) {
            if (!scannedResources.contains(resourceClass) && !isExcluded(resourceClass)) {
                System.out.println("Scanning JAX-RS resource: " + resourceClass.getName());
                scanResource(resourceClass);
                scannedResources.add(resourceClass);
            }
        }
        return discoveredTypes;
    }

    private void scanResource(Class<?> resourceClass) {
        final List<Method> methods = Arrays.asList(resourceClass.getMethods());
        Collections.sort(methods, new Comparator<Method>() {
            @Override
            public int compare(Method o1, Method o2) {
                return o1.getName().compareToIgnoreCase(o2.getName());
            }
        });
        for (Method method : methods) {
            scanResourceMethod(resourceClass, method);
        }
    }

    private void scanResourceMethod(Class<?> resourceClass, Method method) {
        if (isHttpMethod(method)) {
            // JAX-RS specification - 3.3.2.1 Entity Parameters
            final Type entityParameterType = getEntityParameterType(method);
            if (entityParameterType != null) {
                foundType(entityParameterType, resourceClass, method.getName());
            }
            // JAX-RS specification - 3.3.3 Return Type
            final Class<?> returnType = method.getReturnType();
            final Type genericReturnType = method.getGenericReturnType();
            if (returnType == void.class || returnType == Response.class) {
                // no discovered Type
            } else if (genericReturnType instanceof ParameterizedType && returnType == GenericEntity.class) {
                final ParameterizedType parameterizedReturnType = (ParameterizedType) genericReturnType;
                foundType(parameterizedReturnType.getActualTypeArguments()[0], resourceClass, method.getName());
            } else {
                foundType(genericReturnType, resourceClass, method.getName());
            }
        }
        // JAX-RS specification - 3.4.1 Sub Resources
        if (method.isAnnotationPresent(Path.class) && !isHttpMethod(method)) {
            resourceQueue.add(method.getReturnType());
        }
    }

    private void foundType(Type type, Class<?> usedInClass, String usedInMember) {
        if (!isExcluded(type)) {
            discoveredTypes.add(new SourceType<>(type, usedInClass, usedInMember));
        }
    }

    private boolean isExcluded(Type type) {
        final Class<?> cls = Utils.getRawClassOrNull(type);
        if (cls != null && excludes.contains(cls.getName())) {
            return true;
        }
        for (Class<?> standardEntityClass : getStandardEntityClasses()) {
            if (standardEntityClass.isAssignableFrom(cls)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHttpMethod(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(HttpMethod.class)) {
                return true;
            }
        }
        return false;
    }

    private static Type getEntityParameterType(Method method) {
        final Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        for (int i = 0; i < parameterAnnotations.length; i++) {
            if (!hasAnyAnnotation(parameterAnnotations[i], Arrays.asList(
                    MatrixParam.class,
                    QueryParam.class,
                    PathParam.class,
                    CookieParam.class,
                    HeaderParam.class,
                    Context.class,
                    FormParam.class
                    ))) {
                return method.getGenericParameterTypes()[i];
            }
        }
        return null;
    }

    private static boolean hasAnyAnnotation(Annotation[] parameterAnnotations, List<Class<? extends Annotation>> annotationClasses) {
        for (Class<? extends Annotation> annotationClass : annotationClasses) {
            for (Annotation parameterAnnotation : parameterAnnotations) {
                if (annotationClass.isInstance(parameterAnnotation)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Class<?>> getStandardEntityClasses() {
        // JAX-RS specification - 4.2.4 Standard Entity Providers
        return Arrays.asList(
                byte[].class,
                java.lang.String.class,
                java.io.InputStream.class,
                java.io.Reader.class,
                java.io.File.class,
                javax.activation.DataSource.class,
                javax.xml.transform.Source.class,
                javax.xml.bind.JAXBElement.class,
                MultivaluedMap.class,
                StreamingOutput.class,
                java.lang.Boolean.class, java.lang.Character.class, java.lang.Number.class,
                long.class, int.class, short.class, byte.class, double.class, float.class, boolean.class, char.class);
    }

    private static List<String> getDefaultExcludedClassNames() {
        return Arrays.asList(
                "org.glassfish.jersey.media.multipart.FormDataBodyPart"
        );
    }

}
