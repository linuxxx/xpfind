package oblocator;

import java.util.List;

/** 扫描结果 + 元数据（§14.3 可观测性）。 */
public final class ScanResult {

    public final List<Hit> hits;
    public final Stats stats;

    public ScanResult(List<Hit> hits, Stats stats) {
        this.hits = hits;
        this.stats = stats;
    }

    public static final class Stats {
        public int classesScanned;  // dex 阶段扫过的类数
        public int candidates;      // dex 初筛出的候选类数
        public int hitCount;        // 最终命中数（应用 limit 前的完整集大小）
        public long elapsedMs;
        public boolean fromCache;   // 是否走了命中缓存快路径
        public boolean budgetTripped; // 是否因时间/类数预算被截断（此时不写缓存）

        @Override
        public String toString() {
            return "classes=" + classesScanned
                    + " cand=" + candidates
                    + " hits=" + hitCount
                    + " " + elapsedMs + "ms"
                    + " cache=" + fromCache
                    + " budgetTripped=" + budgetTripped;
        }
    }
}
