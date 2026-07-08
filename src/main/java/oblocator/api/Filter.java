package oblocator.api;

import java.lang.reflect.Method;

/**
 * 自定义过滤器。id() 必须稳定，会进入规则 hash（否则缓存无法判断 filter 是否变化）。
 * ok() 在反射阶段对候选方法调用。
 */
public interface Filter {
    String id();
    boolean ok(Class<?> c, Method m);
}
