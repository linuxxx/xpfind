package oblocator;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 纯 Java 最小 dex 解析器（§10.0）。
 * 只做 FAST/NORMAL 初筛需要的事：枚举 class_def，读每个方法的 名字/返回值/参数类型/access_flags，
 * 以及类的 父类/接口/字段数/方法数。不读方法字节码（那是 DEEP v3 的事），不加载任何类。
 *
 * dex 为小端格式。参考 Android dex-format 文档。
 */
public final class Dex {

    private Dex() {}

    /** 类访问回调；返回 false 表示中止整轮扫描（用于预算截断 / first）。 */
    public interface ClassVisitor {
        boolean visit(DexClass c);
    }

    public static final class DexClass {
        public String name;          // a.b.C
        public String superName;     // a.b.Base 或 null
        public String[] interfaces;  // 可能为空数组
        public int accessFlags;
        public int fieldCount;
        public int methodCount;
        public DexMethod[] methods;  // 可能为空数组
    }

    public static final class DexMethod {
        public String name;
        public String ret;           // Class.getName() 形式
        public String[] params;      // Class.getName() 形式
        public int accessFlags;
    }

    private static final String[] NO_STR = new String[0];
    private static final DexMethod[] NO_METHOD = new DexMethod[0];
    private static final int NO_INDEX = 0xffffffff;

    /** 目标 App 的所有 apk（base + split）。 */
    public static String[] apkPaths(Context ctx) {
        ApplicationInfo ai = ctx.getApplicationInfo();
        List<String> paths = new ArrayList<>();
        if (ai.sourceDir != null) paths.add(ai.sourceDir);
        if (ai.splitSourceDirs != null) {
            for (String s : ai.splitSourceDirs) {
                if (s != null) paths.add(s);
            }
        }
        return paths.toArray(new String[0]);
    }

    /**
     * 扫描给定 apk 列表中所有 dex，回调 pkgPrefix 前缀下的类。
     * pkgPrefix 为 null 时回调全部类（配合 allowAll）。
     */
    public static void scan(String[] apkPaths, String pkgPrefix, ClassVisitor visitor) {
        for (String path : apkPaths) {
            try {
                if (!scanZip(new File(path), pkgPrefix, visitor)) return; // visitor 中止
            } catch (Throwable t) {
                Log.w("dex open fail " + path, t);
            }
        }
    }

    /**
     * 扫描任意文件：可以是 raw .dex，也可以是 apk/jar/zip 容器（动态加载 dex 用）。
     * pkgPrefix 为 null 时回调全部类。
     */
    public static void scanFiles(String[] paths, String pkgPrefix, ClassVisitor visitor) {
        for (String path : paths) {
            File f = new File(path);
            if (!f.exists()) {
                Log.w("dex path not found " + path, null);
                continue;
            }
            try {
                if (isRawDex(f)) {
                    byte[] b = readFile(f);
                    if (b != null && !parseDex(b, pkgPrefix, visitor)) return;
                } else {
                    if (!scanZip(f, pkgPrefix, visitor)) return;
                }
            } catch (Throwable t) {
                Log.w("dex file fail " + path, t);
            }
        }
    }

    /**
     * 扫描内存中的字节：raw dex 或 zip/jar 字节流（从 assets 读出来的明文 dex 用）。
     * pkgPrefix 为 null 时回调全部类。
     */
    public static void scanBytes(byte[] data, String pkgPrefix, ClassVisitor visitor) {
        if (data == null || data.length < 8) return;
        try {
            if (data[0] == 'd' && data[1] == 'e' && data[2] == 'x') {
                parseDex(data, pkgPrefix, visitor);
            } else {
                scanZipStream(new ByteArrayInputStream(data), pkgPrefix, visitor);
            }
        } catch (Throwable t) {
            Log.w("dex bytes fail", t);
        }
    }

    // ---- 容器解析 ----

    private static boolean isRootDex(String n) {
        return n.indexOf('/') < 0 && n.startsWith("classes") && n.endsWith(".dex");
    }

    /** 打开 zip/apk/jar，扫描其中根级 classes*.dex。返回 false 表示 visitor 中止。 */
    private static boolean scanZip(File f, String prefix, ClassVisitor visitor) throws Exception {
        ZipFile zf = null;
        try {
            zf = new ZipFile(f);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (isRootDex(e.getName())) {
                    byte[] buf = readFully(zf.getInputStream(e), (int) e.getSize());
                    if (buf != null && !parseDex(buf, prefix, visitor)) return false;
                }
            }
        } finally {
            if (zf != null) try { zf.close(); } catch (Throwable ignore) {}
        }
        return true;
    }

    /** 从 zip 字节流扫描根级 classes*.dex（无随机访问，逐条读）。 */
    private static void scanZipStream(InputStream in, String prefix, ClassVisitor visitor)
            throws Exception {
        ZipInputStream zis = new ZipInputStream(in);
        try {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (isRootDex(e.getName())) {
                    byte[] buf = readCurrentEntry(zis);
                    if (buf != null && !parseDex(buf, prefix, visitor)) return;
                }
            }
        } finally {
            try { zis.close(); } catch (Throwable ignore) {}
        }
    }

    private static boolean isRawDex(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] h = new byte[4];
            int r = in.read(h);
            return r >= 3 && h[0] == 'd' && h[1] == 'e' && h[2] == 'x';
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignore) {}
        }
    }

    private static byte[] readFile(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            return readFully(in, (int) f.length()); // readFully 会关闭 in
        } catch (Throwable t) {
            Log.w("dex read file fail " + f, t);
            if (in != null) try { in.close(); } catch (Throwable ignore) {}
            return null;
        }
    }

    /** 读 ZipInputStream 当前 entry 到结尾，不关闭底层流。 */
    private static byte[] readCurrentEntry(ZipInputStream zis) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(32 * 1024);
        byte[] tmp = new byte[16 * 1024];
        int r;
        while ((r = zis.read(tmp)) != -1) bos.write(tmp, 0, r);
        return bos.toByteArray();
    }

    private static byte[] readFully(InputStream in, int sizeHint) {
        try {
            ByteArrayOutputStream bos =
                    new ByteArrayOutputStream(sizeHint > 0 ? sizeHint : 32 * 1024);
            byte[] tmp = new byte[16 * 1024];
            int r;
            while ((r = in.read(tmp)) != -1) bos.write(tmp, 0, r);
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.w("dex read fail", t);
            return null;
        } finally {
            try { in.close(); } catch (Throwable ignore) {}
        }
    }

    /** 返回 false 表示 visitor 中止。 */
    private static boolean parseDex(byte[] b, String prefix, ClassVisitor visitor) {
        try {
            if (b.length < 112 || b[0] != 'd' || b[1] != 'e' || b[2] != 'x') {
                return true; // 非 dex，跳过
            }
            Reader r = new Reader(b);

            int stringIdsOff = r.u4(60);
            int typeIdsOff = r.u4(68);
            int protoIdsOff = r.u4(76);
            // header 里 method_ids 段：size 在 88，off 在 92。之前误读 88（size）当偏移，
            // 导致 method() 的 base = size + idx*8 落到垃圾区，几乎所有类的方法签名全错/越界。
            int methodIdsOff = r.u4(92);
            int classDefsSize = r.u4(96);
            int classDefsOff = r.u4(100);

            r.stringIdsOff = stringIdsOff;
            r.typeIdsOff = typeIdsOff;
            r.protoIdsOff = protoIdsOff;
            r.methodIdsOff = methodIdsOff;

            // dex 版本（"035".."041"）。041 = container 格式（多 dex 合一，偏移语义不同），本解析器不支持。
            String ver = (b[4] >= '0' && b[4] <= '9')
                    ? new String(b, 4, 3, "US-ASCII") : "?";
            int fileLen = b.length;
            // 头部各段偏移必须落在文件内，class_defs 区间必须完整。否则不是标准单 dex（可能是 041 容器 / 加密残留）。
            boolean hdrOk = inRange(stringIdsOff, fileLen) && inRange(typeIdsOff, fileLen)
                    && inRange(protoIdsOff, fileLen) && inRange(methodIdsOff, fileLen)
                    && classDefsSize >= 0 && classDefsOff >= 0
                    && (long) classDefsOff + (long) classDefsSize * 32 <= fileLen;
            if (!hdrOk) {
                Log.w("dex header out of range: ver=" + ver + " len=" + fileLen
                        + " strIds=" + stringIdsOff + " classDefs=" + classDefsSize + "@" + classDefsOff
                        + (ver.equals("041") ? "  [041 container 格式，暂不支持]" : ""), null);
                return true;
            }
            Log.d("dex ok: ver=" + ver + " len=" + fileLen + " classDefs=" + classDefsSize);

            int skipped = 0;
            for (int ci = 0; ci < classDefsSize; ci++) {
                // 单类隔离：某个类结构读崩了只跳过它，不牵连整个 dex 的其余 3000+ 类。
                DexClass dc = null;
                String cn = "#" + ci;
                try {
                    int co = classDefsOff + ci * 32;
                    int classTypeIdx = r.u4(co);
                    int accessFlags = r.u4(co + 4);
                    int superIdx = r.u4(co + 8);
                    int itfOff = r.u4(co + 12);
                    int classDataOff = r.u4(co + 24);

                    String cname = r.typeName(classTypeIdx);
                    if (prefix != null && !cname.startsWith(prefix)) continue;
                    cn = cname;

                    dc = new DexClass();
                    dc.name = cname;
                    dc.accessFlags = accessFlags;
                    dc.superName = (superIdx == NO_INDEX) ? null : r.typeName(superIdx);
                    dc.interfaces = r.typeList(itfOff);
                    fillMethods(r, dc, classDataOff);
                } catch (DexFormatException fe) {
                    skipped++;
                    Log.d("dex skip class " + cn + ": " + fe.getMessage());
                    continue; // 下一个类会重置 r.p，自愈
                }
                if (!visitor.visit(dc)) return false;
            }
            if (skipped > 0) {
                Log.w("dex: skipped " + skipped + "/" + classDefsSize + " malformed classes", null);
            }
            return true;
        } catch (DexFormatException fe) {
            // 头部级/致命结构错误：只记一行，不吐整条堆栈。
            Log.w("dex parse skip: " + fe.getMessage(), null);
            return true;
        } catch (Throwable t) {
            Log.w("dex parse fail", t);
            return true; // 单个 dex 解析失败不影响其它
        }
    }

    /** off 落在 [0, len) 内（段起始偏移的粗校验）。 */
    private static boolean inRange(int off, int len) {
        return off >= 0 && off < len;
    }

    /** dex 结构不合法 / 不支持的格式。仅内部使用，被 parseDex 捕获后安全跳过。 */
    private static final class DexFormatException extends RuntimeException {
        DexFormatException(String msg) { super(msg); }
    }

    private static void fillMethods(Reader r, DexClass dc, int classDataOff) {
        if (classDataOff == 0) {
            dc.methods = NO_METHOD;
            return;
        }
        if (classDataOff < 0 || classDataOff >= r.len) {
            throw new DexFormatException("classDataOff " + classDataOff + " len " + r.len);
        }
        r.p = classDataOff;
        int staticFields = r.uleb();
        int instanceFields = r.uleb();
        int directMethods = r.uleb();
        int virtualMethods = r.uleb();

        // 计数来自不可信字节：任一数量都不可能超过缓冲区长度（每个成员至少 1 字节）。
        long total = (long) staticFields + instanceFields + directMethods + virtualMethods;
        if (staticFields < 0 || instanceFields < 0 || directMethods < 0 || virtualMethods < 0
                || total > r.len) {
            throw new DexFormatException("member count s=" + staticFields + " i=" + instanceFields
                    + " d=" + directMethods + " v=" + virtualMethods + " len=" + r.len);
        }

        dc.fieldCount = staticFields + instanceFields;
        dc.methodCount = directMethods + virtualMethods;

        // 跳过 encoded_field（各 2 个 uleb）
        for (int i = 0; i < staticFields; i++) { r.uleb(); r.uleb(); }
        for (int i = 0; i < instanceFields; i++) { r.uleb(); r.uleb(); }

        DexMethod[] ms = new DexMethod[directMethods + virtualMethods];
        int idx = 0;
        idx = readMethods(r, ms, idx, directMethods);
        idx = readMethods(r, ms, idx, virtualMethods);
        dc.methods = ms;
    }

    /** direct 与 virtual 是两个独立列表，method_idx_diff 各自从 0 累加。 */
    private static int readMethods(Reader r, DexMethod[] out, int outIdx, int count) {
        int methodIdx = 0;
        for (int i = 0; i < count; i++) {
            int diff = r.uleb();
            methodIdx += diff;
            int accessFlags = r.uleb();
            r.uleb(); // code_off，v1 不需要
            out[outIdx++] = r.method(methodIdx, accessFlags);
        }
        return outIdx;
    }

    /** dex 读取器 + 各索引表解析。所有读取都做边界校验，越界抛 DexFormatException（被 parseDex 捕获）。 */
    private static final class Reader {
        final byte[] b;
        final int len;
        int p;

        int stringIdsOff;
        int typeIdsOff;
        int protoIdsOff;
        int methodIdsOff;

        Reader(byte[] b) { this.b = b; this.len = b.length; }

        /** 校验 [off, off+n) 落在缓冲区内。 */
        private void req(int off, int n) {
            if (off < 0 || n < 0 || off > len - n) {
                throw new DexFormatException("read oob off=" + off + " n=" + n + " len=" + len);
            }
        }

        int u2(int off) {
            req(off, 2);
            return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
        }

        int u4(int off) {
            req(off, 4);
            return (b[off] & 0xff)
                    | ((b[off + 1] & 0xff) << 8)
                    | ((b[off + 2] & 0xff) << 16)
                    | ((b[off + 3] & 0xff) << 24);
        }

        /** uleb128 at current p，前进 p。 */
        int uleb() {
            int result = 0, shift = 0, cur;
            do {
                if (p < 0 || p >= len) throw new DexFormatException("uleb oob p=" + p + " len=" + len);
                if (shift > 35) throw new DexFormatException("uleb too long");
                cur = b[p++] & 0xff;
                result |= (cur & 0x7f) << shift;
                shift += 7;
            } while ((cur & 0x80) != 0);
            return result;
        }

        /** string_id[idx] -> 字符串。 */
        String str(int strIdx) {
            if (strIdx < 0) throw new DexFormatException("str idx " + strIdx);
            int off = u4(stringIdsOff + strIdx * 4);
            int q = off, shift = 0, result = 0, cur;
            do {
                if (q < 0 || q >= len) throw new DexFormatException("str len oob q=" + q);
                if (shift > 35) throw new DexFormatException("str len too long");
                cur = b[q++] & 0xff;
                result |= (cur & 0x7f) << shift;
                shift += 7;
            } while ((cur & 0x80) != 0);
            return mutf8(q, result);
        }

        /** type_id[idx] 对应的描述符字符串。 */
        String typeDesc(int typeIdx) {
            int strIdx = u4(typeIdsOff + typeIdx * 4);
            return str(strIdx);
        }

        /** type_id[idx] -> Class.getName() 形式类名。 */
        String typeName(int typeIdx) {
            return descToName(typeDesc(typeIdx));
        }

        /** type_list（接口 / 参数列表） -> 名字数组。off==0 返回空数组。 */
        String[] typeList(int off) {
            if (off == 0) return NO_STR;
            int size = u4(off);
            if (size <= 0) return NO_STR;
            if (size > len) throw new DexFormatException("typeList size " + size + " > len " + len);
            String[] out = new String[size];
            for (int i = 0; i < size; i++) {
                int typeIdx = u2(off + 4 + i * 2);
                out[i] = typeName(typeIdx);
            }
            return out;
        }

        /** method_id[idx] + access -> DexMethod。 */
        DexMethod method(int methodIdx, int accessFlags) {
            int base = methodIdsOff + methodIdx * 8;
            int protoIdx = u2(base + 2);
            int nameIdx = u4(base + 4);

            DexMethod m = new DexMethod();
            m.name = str(nameIdx);
            m.accessFlags = accessFlags;

            int po = protoIdsOff + protoIdx * 12;
            int retTypeIdx = u4(po + 4);
            int paramsOff = u4(po + 8);
            m.ret = typeName(retTypeIdx);
            m.params = typeList(paramsOff);
            return m;
        }

        String mutf8(int q, int utf16len) {
            if (utf16len < 0 || utf16len > len) {
                throw new DexFormatException("mutf8 len " + utf16len + " len " + len);
            }
            char[] out = new char[utf16len];
            int oi = 0;
            while (oi < utf16len) {
                if (q >= len) throw new DexFormatException("mutf8 oob q=" + q);
                int a = b[q++] & 0xff;
                if (a < 0x80) {
                    out[oi++] = (char) a;
                } else if ((a & 0xe0) == 0xc0) {
                    if (q >= len) throw new DexFormatException("mutf8 oob2");
                    int b2 = b[q++] & 0xff;
                    out[oi++] = (char) (((a & 0x1f) << 6) | (b2 & 0x3f));
                } else if ((a & 0xf0) == 0xe0) {
                    if (q + 1 >= len) throw new DexFormatException("mutf8 oob3");
                    int b2 = b[q++] & 0xff;
                    int b3 = b[q++] & 0xff;
                    out[oi++] = (char) (((a & 0x0f) << 12) | ((b2 & 0x3f) << 6) | (b3 & 0x3f));
                } else {
                    out[oi++] = (char) a; // 罕见，类/方法名不会走到
                }
            }
            return new String(out, 0, oi);
        }
    }

    /** dex 类型描述符 -> Class.getName() 形式。 */
    static String descToName(String d) {
        int brk = 0;
        while (brk < d.length() && d.charAt(brk) == '[') brk++;
        String base = d.substring(brk);
        if (brk == 0) {
            if (base.length() == 1) return prim(base.charAt(0));
            // "La/b/C;" -> "a.b.C"
            return base.substring(1, base.length() - 1).replace('/', '.');
        }
        // 数组：保持 getName() 形式
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < brk; i++) sb.append('[');
        if (base.length() == 1) {
            sb.append(base.charAt(0));                 // 如 [I
        } else {
            sb.append('L')
              .append(base.substring(1, base.length() - 1).replace('/', '.'))
              .append(';');                            // 如 [Ljava.lang.String;
        }
        return sb.toString();
    }

    private static String prim(char c) {
        switch (c) {
            case 'I': return "int";
            case 'J': return "long";
            case 'Z': return "boolean";
            case 'B': return "byte";
            case 'C': return "char";
            case 'S': return "short";
            case 'F': return "float";
            case 'D': return "double";
            case 'V': return "void";
            default: return String.valueOf(c);
        }
    }
}
