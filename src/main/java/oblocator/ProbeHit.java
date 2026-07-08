package oblocator;

import java.util.Arrays;

/**
 * dex 初筛层的命中（{@link OL#probe}）。只来自 dex 字节解析，不加载类、不含反射 Method。
 * 用于本地/离线快速验证规则是否合理。
 */
public final class ProbeHit {

    public final String cls;      // a.b.C
    public final String name;     // 方法名
    public final String ret;      // 返回值（Class.getName() 形式）
    public final String[] params; // 参数类型（Class.getName() 形式）
    public final int flags;       // access_flags

    ProbeHit(String cls, String name, String ret, String[] params, int flags) {
        this.cls = cls;
        this.name = name;
        this.ret = ret;
        this.params = params;
        this.flags = flags;
    }

    @Override
    public String toString() {
        return cls + "#" + name + Arrays.toString(params) + "->" + ret
                + " flags=0x" + Integer.toHexString(flags);
    }
}
