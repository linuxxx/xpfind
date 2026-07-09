package oblocator.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import oblocator.Sig;

/**
 * 规则模型（基类）。Ri.MR / Ri.ApiR 继承它。字段为 null / 默认值表示 "不关心"。
 * 三态 Boolean：null=不关心，TRUE=必须满足，FALSE=必须不满足。
 */
public class Rule {

    /** args 里的单格通配符：该位置接受任意参数类型（用于混淆/不关心的那一格）。 */
    public static final String ANY = "*";

    public String id;

    // 类条件
    public String cls;        // 类全名精确
    public String hasCls;     // 类全名包含

    // 方法名条件
    public String name;       // 方法名精确
    public String hasName;    // 方法名包含

    // 签名条件
    public String ret;        // 返回值（Class.getName() 形式，如 java.lang.String / int / [B）
    public String retSuper;   // 返回值类型的父类/祖先（如 java.lang.Enum）；dex 层按父类链判定，抗混淆改名
    public String[] args;     // 参数类型（同上）
    public Integer argc;      // 参数数量精确
    public Integer min;       // 参数数量下限
    public Integer max;       // 参数数量上限

    // 修饰符（三态）
    public Boolean isStatic;
    public Boolean isPublic;
    public Boolean isPrivate;
    public Boolean isProtected;
    public Boolean isNative;
    public Boolean isSync;

    // NORMAL 结构特征（null=不关心）
    public String superCls;   // 父类全名精确
    public String hasItf;     // 实现的接口全名（任一包含即可）
    public Integer fieldc;    // 字段数量
    public Integer methodc;   // 方法数量

    // DEEP（v3）保留字段，v1 不使用
    public List<String> strs = new ArrayList<>();
    public List<String> calls = new ArrayList<>();

    // 数量控制（不进 hash：缓存始终存完整集，first/limit 只在读出后作用于返回）
    public boolean first;
    public int limit = 20;

    // 匹配后置过滤（不改变命中的语义定位，只做自定义收窄）
    public Filter filter;

    public String hash() {
        return Sig.md5(stable());
    }

    /** 参与规则 hash 的稳定串。刻意不含 first/limit（它们不改变命中集本身）。 */
    public String stable() {
        StringBuilder sb = new StringBuilder();
        sb.append("cls=").append(cls).append(';');
        sb.append("hasCls=").append(hasCls).append(';');
        sb.append("name=").append(name).append(';');
        sb.append("hasName=").append(hasName).append(';');
        sb.append("ret=").append(ret).append(';');
        sb.append("retSuper=").append(retSuper).append(';');
        sb.append("args=").append(Arrays.toString(args)).append(';');
        sb.append("argc=").append(argc).append(';');
        sb.append("min=").append(min).append(';');
        sb.append("max=").append(max).append(';');
        sb.append("static=").append(isStatic).append(';');
        sb.append("public=").append(isPublic).append(';');
        sb.append("private=").append(isPrivate).append(';');
        sb.append("protected=").append(isProtected).append(';');
        sb.append("native=").append(isNative).append(';');
        sb.append("sync=").append(isSync).append(';');
        sb.append("superCls=").append(superCls).append(';');
        sb.append("hasItf=").append(hasItf).append(';');
        sb.append("fieldc=").append(fieldc).append(';');
        sb.append("methodc=").append(methodc).append(';');
        sb.append("strs=").append(strs).append(';');
        sb.append("calls=").append(calls).append(';');
        sb.append("filter=").append(filter == null ? null : filter.id()).append(';');
        return sb.toString();
    }
}
