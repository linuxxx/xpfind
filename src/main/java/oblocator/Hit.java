package oblocator;

import java.lang.reflect.Method;

import oblocator.api.Rule;

/** 命中结果。method 在缓存反序列化后为 null，需经 Cache.resolve 重新绑定。 */
public final class Hit {

    public String cls;
    public String name;
    public String ret;
    public String[] args;
    public int mod;
    public Method method;
    public Rule rule;

    public String sig() {
        return method != null ? Sig.of(method) : Sig.of(cls, name, ret, args, mod);
    }

    @Override
    public String toString() {
        return sig();
    }
}
