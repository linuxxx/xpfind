package oblocator;

/** 异步扫描完成回调（§14.1）。用自定义接口而非 java.util.function.Consumer，兼容 minSdk 21。 */
public interface OnScan {
    void on(ScanResult result);
}
