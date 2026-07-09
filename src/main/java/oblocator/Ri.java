package oblocator;

import oblocator.api.Filter;
import oblocator.api.Rule;

/** 规则构建器入口。 */
public final class Ri {

    private Ri() {}

    /** 方法规则。 */
    public static MR m() {
        return new MR();
    }

    /** 稳定 API Hook 规则（TRACE，第二版实现）。 */
    public static ApiR api() {
        return new ApiR();
    }

    /** 方法规则构建器。所有 setter 返回 this，链式调用。 */
    public static final class MR extends Rule {

        public MR ret(Class<?> c) {
            this.ret = c.getName();
            return this;
        }

        public MR ret(String name) {
            this.ret = name;
            return this;
        }

        /**
         * 返回值类型的父类/祖先（如 {@code Enum.class}）。在 dex 层沿父类链判定，
         * 不需要写死被混淆的返回值类名，抗 build 更新。可与 args 一起收窄。
         */
        public MR retSuper(Class<?> c) {
            this.retSuper = c.getName();
            return this;
        }

        public MR retSuper(String name) {
            this.retSuper = name;
            return this;
        }

        public MR args(Class<?>... cs) {
            this.args = new String[cs.length];
            for (int i = 0; i < cs.length; i++) {
                this.args[i] = cs[i].getName();
            }
            return this;
        }

        public MR args(String... names) {
            this.args = names;
            return this;
        }

        /**
         * 混合参数匹配：每一格可传 {@link Class}（精确/父类）或字符串（类全名，或通配符
         * {@link Rule#ANY "*"} 表示该格任意类型）。用于「3 个参数里有一格是混淆类」的场景：
         * <pre>argsLike(String.class, Rule.ANY, int.class)   // 中间那格通配</pre>
         * 已知混淆类的稳定父类时，配 {@code .normal()} 直接把父类写进来更精确（弱匹配按父类链判定）。
         */
        public MR argsLike(Object... items) {
            this.args = new String[items.length];
            for (int i = 0; i < items.length; i++) {
                Object o = items[i];
                this.args[i] = (o instanceof Class) ? ((Class<?>) o).getName() : String.valueOf(o);
            }
            return this;
        }

        public MR argc(int n) {
            this.argc = n;
            return this;
        }

        public MR min(int n) {
            this.min = n;
            return this;
        }

        public MR max(int n) {
            this.max = n;
            return this;
        }

        // 无参 = 必须有该修饰符；布尔重载 = 显式要求有/无（false 即"必须不是"）。

        public MR static_() { return static_(true); }
        public MR static_(boolean v) { this.isStatic = v; return this; }

        public MR public_() { return public_(true); }
        public MR public_(boolean v) { this.isPublic = v; return this; }

        public MR private_() { return private_(true); }
        public MR private_(boolean v) { this.isPrivate = v; return this; }

        public MR protected_() { return protected_(true); }
        public MR protected_(boolean v) { this.isProtected = v; return this; }

        public MR native_() { return native_(true); }
        public MR native_(boolean v) { this.isNative = v; return this; }

        public MR synchronized_() { return synchronized_(true); }
        public MR synchronized_(boolean v) { this.isSync = v; return this; }

        public MR cls(String name) {
            this.cls = name;
            return this;
        }

        public MR hasCls(String value) {
            this.hasCls = value;
            return this;
        }

        public MR name(String name) {
            this.name = name;
            return this;
        }

        public MR hasName(String value) {
            this.hasName = value;
            return this;
        }

        public MR superCls(String name) {
            this.superCls = name;
            return this;
        }

        public MR hasItf(String name) {
            this.hasItf = name;
            return this;
        }

        public MR fieldc(int n) {
            this.fieldc = n;
            return this;
        }

        public MR methodc(int n) {
            this.methodc = n;
            return this;
        }

        public MR str(String value) {
            this.strs.add(value);
            return this;
        }

        public MR call(String value) {
            this.calls.add(value);
            return this;
        }

        public MR filter(Filter filter) {
            this.filter = filter;
            return this;
        }

        public MR first() {
            this.first = true;
            this.limit = 1;
            return this;
        }

        public MR limit(int n) {
            this.limit = n;
            return this;
        }
    }

    /** 稳定 API 规则（TRACE）。字段保留，第二版实现具体 Hook。 */
    public static final class ApiR extends Rule {

        public boolean json;
        public boolean crypto;
        public boolean net;
        public boolean sp;
        public boolean stack;

        public ApiR json() { this.json = true; return this; }
        public ApiR crypto() { this.crypto = true; return this; }
        public ApiR net() { this.net = true; return this; }
        public ApiR sp() { this.sp = true; return this; }

        public ApiR all() {
            this.json = true;
            this.crypto = true;
            this.net = true;
            this.sp = true;
            return this;
        }

        public ApiR stack() { this.stack = true; return this; }
    }
}
