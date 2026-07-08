# ObLocator

Xposed 环境下定位混淆 Java 方法的库。

## 当前实现状态（第一版，见方案 §30）

已实现：

- `FAST` / `NORMAL` 两种模式
- **纯 Java dex 层签名初筛**（不逐类反射，不用废弃的 `DexFile.entries`，不引入任何 native）
- 两阶段扫描：dex 初筛缩小候选类 → 只对候选 `Class.forName` 反射做最终匹配
- 三层缓存：L1 内存 + L2/L3 文件（JSON，原子写），命中缓存读出后反射校验
- 异步扫描 `.async()` + 时间/类数预算 `.budget()` / `.maxClasses()`
- `limit` / `first` / 自定义 `Filter`
- 进程门控（默认只主进程）、`pkg` 必填门控（`allowAll()` 显式绕过）

> **本库只定位，不做 Hook。** 终止方法 `find` / `run` 都返回 `List<Hit>`；
> 拿到 `Hit.method`（`java.lang.reflect.Method`）后由调用方用 Xposed API 自己 Hook。

未实现（预留 API，调用即抛 / 占位）：

- `DEEP`（dex 字节码字符串/调用特征，v3，将用纯 Java dex 解析，**不用 DexKit**，见方案 §10.0）
- `TRACE`（内置 API Hook + 调用栈反推，v2）
- `dyn()` 动态 dex（v3+）

## 构建

```bash
./gradlew assembleRelease
```

产物为 AAR；其中 `classes.jar` 即可作为 Xposed 模块的编译期依赖。
`de.robv.android.xposed:api` 为 `compileOnly`，运行期由框架提供。

> 注：本仓库开发环境无 Android SDK，源码未在本机编译验证；
> dex 描述符转换与 LEB128 解析等纯逻辑已用独立 JUnit 式测试确认（见提交说明）。

## 用法

> 全部 API 与场景速查见 [`用法.md`](用法.md)。下面是最常用的几种。

在 Xposed 入口 `handleLoadPackage` 中：

```java
public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
    if (!"com.target.app".equals(lpparam.packageName)) return;

    // 建议异步，避免拖慢宿主启动（方案 §14.1）
    OL.with(appContext, lpparam.classLoader)
      .pkg("com.target.app")
      .normal()
      .budget(300)          // 最多扫 300ms，超时降级
      .async(result -> {
          for (Hit h : result.hits) Log.i("hit: " + h.sig());
      });
}
```

同步快速定位一个 static String 方法：

```java
OL.with(ctx, cl)
  .pkg("com.target.app")
  .fast().log()
  .find(Ri.m().ret(String.class).args(Context.class, String.class).static_());
```

只拿命中 + 统计（不 Hook 是默认，也是唯一行为）：

```java
ScanResult r = OL.with(ctx, cl).pkg("com.target.app").normal()
    .rule(Ri.m().ret(JSONObject.class).argc(1))
    .runWithStats();
Log.i(r.stats.toString());   // classes=.. cand=.. hits=.. 12ms cache=false ...
```

拿到命中后，Hook 由调用方自己做：

```java
for (Hit h : hits) {
    XposedBridge.hookMethod(h.method, new XC_MethodHook() { /* 你自己的逻辑 */ });
}
```

## 包结构

```
oblocator/
├── OL          主入口（链式构建 + 执行）
├── Ri          规则构建器（Ri.m() / Ri.api()）
├── Mode        FAST / NORMAL / DEEP / TRACE
├── Hit         命中结果（含 method：可直接 Hook 的反射 Method）
├── ScanResult  命中 + 扫描元数据（stats）
├── ProbeHit    dex 初筛层命中（.probe() 返回）
├── OnScan      异步扫描完成回调（.async 的参数）
├── Scan        扫描调度
├── Match       两级匹配（dex / 反射，含弱匹配）
├── Dex         纯 Java 最小 dex 解析器
├── Cache       三层缓存
├── Sig / Log / Proc   工具
└── api/
    ├── Filter  自定义过滤器
    └── Rule    规则模型（基类）
```
# xpfind
