# xpfind

Xposed 环境下定位混淆 Java 方法的库。

## 当前实现状态（第一版）

已实现：

- `FAST` / `NORMAL` 两种模式
- **纯 Java dex 层签名初筛**（不逐类反射，不用废弃的 `DexFile.entries`，不引入任何 native）
- 两阶段扫描：dex 初筛缩小候选类 → 只对候选 `Class.forName` 反射做最终匹配
- 三层缓存：L1 内存 + L2/L3 文件（JSON，原子写），命中缓存读出后反射校验
- 异步扫描 `.async()` + 时间/类数预算 `.budget()` / `.maxClasses()`
- `limit` / `first` / 自定义 `Filter`
- **方法体条件** `.str()` / `.call()`：按需解析 dex `code_item`（字符串常量 / invoke 目标），字符串常量抗混淆，是强锚点；不带该条件时走零开销快路径
- **单格参数通配符** `Rule.ANY`（`"*"`）/ `.argsLike()`：匹配被混淆的那一格参数类型
- 进程门控（默认只主进程）、`pkg` 必填门控（`allowAll()` 显式绕过）

> **本库只定位，不做 Hook。** 终止方法 `find` / `run` 都返回 `List<Hit>`；
> 拿到 `Hit.method`（`java.lang.reflect.Method`）后由调用方用 Xposed API 自己 Hook。

> 方法体特征（字符串常量 / 调用目标）已作为 `FAST`/`NORMAL` 下的 `.str()` / `.call()` 条件落地（纯 Java dex 解析，**不用 DexKit**）。

未实现（预留 API，调用即抛 / 占位）：

- `DEEP` 模式外壳（更完整的字节码特征匹配，v3）
- `TRACE`（内置 API Hook + 调用栈反推，v2）
- `dyn()` 动态 dex（v3+）

## 构建

```bash
./gradlew jar
```

产物为 `build/libs/xpfind.jar`（纯 Java 库，无 res/assets/JNI，不需要 AAR）。
Xposed 模块把它作为编译期依赖即可：

```gradle
dependencies {
    compileOnly files('libs/xpfind.jar')
}
```

> 本库自身只在编译期依赖 `android.jar`（提供 `Context` / `android.util.Log` 等符号，运行期由系统提供），
> **不依赖 Xposed** —— Hook 由调用方在自己的模块里用 `Hit.method` 实现。
> 构建需要 Android SDK：默认读 `local.properties` 的 `sdk.dir`（回退环境变量 `ANDROID_HOME`），用 `android-34` 平台的 `android.jar`。

> 已在本机用 `./gradlew jar test` 编译并跑通单元测试（dex 描述符转换 / LEB128 / `Match.dexPass` 初筛层，见 `src/test`）；
> 反射确认阶段需真机（本地 JVM 加载不了 dalvik 类）。

## 用法

> 完整 API 与规则条件见 [`快速上手.md`](快速上手.md)。下面是最常用的几种。

在 Xposed 入口 `handleLoadPackage` 中：

```java
public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
    if (!"com.target.app".equals(lpparam.packageName)) return;

    // 要 Hook 早期就可能被调用的方法：用同步定位，保证 Hook 先于首次调用装上。
    // 扫描只有冷启动贵，之后走缓存；再用 FAST + 精确 pkg + budget() 压低耗时。
    List<Hit> hits = OL.with(appContext, lpparam.classLoader)
      .pkg("com.target.app")
      .fast()
      .budget(300)          // 最多扫 300ms，超时降级
      .find(Ri.m().ret(String.class).args(Context.class, String.class).static_());

    for (Hit h : hits) {
        XposedBridge.hookMethod(h.method, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Log.i("hit: " + h.sig());
            }
        });
    }
    // 目标只在后续用户操作才触发、或只需拿到方法位置时，才改用
    //   .async(result -> { ... })  避免拖慢启动——但它会漏掉扫描完成前对目标的调用。
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
