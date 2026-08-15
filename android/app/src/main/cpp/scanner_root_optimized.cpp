/**
 * GG-AI Root Memory Scanner v2 — 协议加固版
 *
 * 相比 v1 的关键变化：
 *
 * 1. 结果集常驻本进程（g_sets），搜索只回 count，不再把百万地址序列化成一行 JSON。
 *    过滤/模糊比对/取值全部在进程内完成，Kotlin 侧按页取。
 * 2. 每条命令必定输出且仅输出一行 JSON，出错也回 {"status":"error"}，
 *    杜绝调用方 readLine() 永久阻塞。
 * 3. 搜索携带显式 type，不再靠数值形态猜 dword/float；补齐 byte/word/qword。
 * 4. 结果上限由调用方传 limit 控制，并如实回报 truncated。
 * 5. 分块读取带重���，修正跨块漏检；区域尾部不足一块的部分也扫。
 * 6. 地址一律用 PRIx64 输出（v1 的 %lx 在 32 位 ABI 上会截断高位）。
 * 7. stdin 按行动态读取，不再受固定 64KB 缓冲限制。
 */

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <cinttypes>
#include <cerrno>
#include <string>
#include <vector>
#include <map>
#include <fcntl.h>
#include <unistd.h>
#include <sys/time.h>

#define PROTO_VERSION 2
#define SCANNER_VERSION "gg-scanner-2.0"

static const size_t   CHUNK_SIZE      = 256 * 1024;  // 单次 pread64 块大小
static const size_t   MEM_PAGE        = 4096;
static const uint64_t DEFAULT_LIMIT   = 1000000;
static const uint64_t MAX_LIMIT       = 8000000;
static const size_t   MAX_READ_SIZE   = 1024 * 1024; // 单次 read 上限
static const size_t   MAX_READ_MANY   = 4096;        // read_many 地址数上限
static const size_t   MAX_SETS        = 8;
static const uint64_t MAX_TOTAL_ADDRS = 4000000;     // 所有结果集地址总量上限
static const size_t   MAX_PAGE_LIMIT  = 1000;        // get_results 单页上限

struct Region { uint64_t start; uint64_t size; };

struct ResultSet {
    std::vector<uint64_t> addrs;
    std::vector<uint8_t>  snapshot;   // addrs.size() * type_size，用于模糊比对
    std::string           type;
    size_t                type_size;
    ResultSet() : type("dword"), type_size(4) {}
};

static std::map<int, ResultSet> g_sets;
static int g_next_set = 1;
static int g_mem_fd   = -1;
static int g_mem_pid  = -1;

// ==================== 输出 ====================

static void emit_error(const char* msg) {
    printf("{\"status\":\"error\",\"msg\":\"%s\"}\n", msg);
    fflush(stdout);
}

static uint64_t now_ms() {
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    return (uint64_t)tv.tv_sec * 1000ULL + (uint64_t)tv.tv_usec / 1000ULL;
}

// ==================== 类型 ====================

static size_t type_size_of(const std::string& t) {
    if (t == "byte")   return 1;
    if (t == "word")   return 2;
    if (t == "dword")  return 4;
    if (t == "qword")  return 8;
    if (t == "float")  return 4;
    if (t == "double") return 8;
    return 0;
}

// ==================== 十六进制 ====================

static int hex_nibble(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static bool hex_to_bytes(const std::string& hex, std::vector<uint8_t>& out) {
    if (hex.size() % 2 != 0) return false;
    out.clear();
    out.reserve(hex.size() / 2);
    for (size_t i = 0; i < hex.size(); i += 2) {
        int hi = hex_nibble(hex[i]);
        int lo = hex_nibble(hex[i + 1]);
        if (hi < 0 || lo < 0) return false;
        out.push_back((uint8_t)((hi << 4) | lo));
    }
    return true;
}

static void bytes_to_hex(const uint8_t* b, size_t n, std::string& out) {
    static const char* D = "0123456789abcdef";
    out.clear();
    out.reserve(n * 2);
    for (size_t i = 0; i < n; i++) {
        out.push_back(D[(b[i] >> 4) & 0xF]);
        out.push_back(D[b[i] & 0xF]);
    }
}

// ==================== 极简 JSON 取值（针对本协议的固定形态） ====================

// 定位 "key" 后跟冒号的值起始处，容忍键前后与冒号两侧的空白。
// 会跳过恰好出现在字符串值里的同名片段���要求后面确实跟冒号）。
static const char* json_find(const char* line, const char* key) {
    std::string pat = std::string("\"") + key + "\"";
    const char* p = line;
    while ((p = strstr(p, pat.c_str())) != nullptr) {
        const char* q = p + pat.size();
        while (*q == ' ' || *q == '\t') q++;
        if (*q == ':') {
            q++;
            while (*q == ' ' || *q == '\t') q++;
            return q;
        }
        p += pat.size();
    }
    return nullptr;
}

static bool json_str(const char* line, const char* key, std::string& out) {
    const char* p = json_find(line, key);
    if (!p || *p != '"') return false;
    p++;
    const char* e = strchr(p, '"');
    if (!e) return false;
    out.assign(p, (size_t)(e - p));
    return true;
}

static bool json_i64(const char* line, const char* key, int64_t& out) {
    const char* p = json_find(line, key);
    if (!p) return false;
    char* end = nullptr;
    long long v = strtoll(p, &end, 0);
    if (end == p) return false;
    out = (int64_t)v;
    return true;
}

static bool json_u64(const char* line, const char* key, uint64_t& out) {
    const char* p = json_find(line, key);
    if (!p) return false;
    char* end = nullptr;
    unsigned long long v = strtoull(p, &end, 0);
    if (end == p) return false;
    out = (uint64_t)v;
    return true;
}

static bool json_double(const char* line, const char* key, double& out) {
    const char* p = json_find(line, key);
    if (!p) return false;
    char* end = nullptr;
    double v = strtod(p, &end);
    if (end == p) return false;
    out = v;
    return true;
}

static bool parse_regions(const char* line, std::vector<Region>& out) {
    const char* p = json_find(line, "regions");
    if (!p || *p != '[') return false;
    p++;
    const char* close = strchr(p, ']');
    while (*p) {
        const char* s = json_find(p, "start");
        const char* z = json_find(p, "size");
        if (!s || !z) break;
        if (close && (s > close || z > close)) break;
        Region r;
        r.start = strtoull(s, nullptr, 0);
        r.size  = strtoull(z, nullptr, 0);
        if (r.size > 0) out.push_back(r);
        p = strchr(z, '}');
        if (!p) break;
        p++;
    }
    return !out.empty();
}

// 解析 "addrs":["7f12","7f16"] 形式的十六进制地址数组
static bool parse_addr_array(const char* line, const char* key,
                             std::vector<uint64_t>& out, size_t maxn) {
    const char* p = json_find(line, key);
    if (!p || *p != '[') return false;
    p++;
    const char* close = strchr(p, ']');
    if (!close) return false;
    while (p < close) {
        const char* q = strchr(p, '"');
        if (!q || q > close) break;
        q++;
        const char* e = strchr(q, '"');
        if (!e || e > close) break;
        std::string tok(q, (size_t)(e - q));
        out.push_back(strtoull(tok.c_str(), nullptr, 16));
        if (out.size() > maxn) return false;   // 超限视为错误，不静默截断
        p = e + 1;
    }
    return true;
}

// ==================== /proc/pid/mem ====================

static bool ensure_mem(int pid) {
    if (g_mem_fd >= 0 && g_mem_pid == pid) return true;
    if (g_mem_fd >= 0) { close(g_mem_fd); g_mem_fd = -1; g_mem_pid = -1; }
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/mem", pid);
    int fd = open(path, O_RDWR);
    if (fd < 0) return false;
    g_mem_fd  = fd;
    g_mem_pid = pid;
    return true;
}

// ==================== 结果集管理 ====================

static uint64_t total_addrs() {
    uint64_t n = 0;
    for (std::map<int, ResultSet>::iterator it = g_sets.begin(); it != g_sets.end(); ++it)
        n += (uint64_t)it->second.addrs.size();
    return n;
}

static void evict_if_needed() {
    while (g_sets.size() > MAX_SETS ||
           (total_addrs() > MAX_TOTAL_ADDRS && g_sets.size() > 1)) {
        g_sets.erase(g_sets.begin());   // id 最小者即最旧
    }
}

// 读取一批地址的当前值；失败的位置 ok=0
static void read_values(const std::vector<uint64_t>& addrs, size_t tsz,
                        std::vector<uint8_t>& vals, std::vector<uint8_t>& ok) {
    vals.assign(addrs.size() * tsz, 0);
    ok.assign(addrs.size(), 0);
    for (size_t i = 0; i < addrs.size(); i++) {
        ssize_t got = pread64(g_mem_fd, &vals[i * tsz], tsz, (off64_t)addrs[i]);
        if (got == (ssize_t)tsz) ok[i] = 1;
    }
}

// snap 非空时直接采用（扫描/过滤过程中已经读到的值，零额外开销）；
// 为空才回落到逐地址读取。
static int store_set(std::vector<uint64_t>& addrs, const std::string& type, size_t tsz,
                     std::vector<uint8_t>* snap) {
    int id = g_next_set++;
    ResultSet rs;
    rs.addrs.swap(addrs);
    rs.type = type;
    rs.type_size = tsz;
    if (snap && snap->size() == rs.addrs.size() * tsz) {
        rs.snapshot.swap(*snap);
    } else {
        std::vector<uint8_t> ok;
        read_values(rs.addrs, tsz, rs.snapshot, ok);
    }
    g_sets[id] = rs;
    evict_if_needed();
    return id;
}

static void emit_set_result(int id, size_t count, bool truncated, uint64_t ms) {
    printf("{\"status\":\"ok\",\"set\":%d,\"count\":%zu,\"truncated\":%s,\"elapsed_ms\":%" PRIu64 "}\n",
           id, count, truncated ? "true" : "false", ms);
    fflush(stdout);
}

// ==================== 扫描 ====================

template <typename T> struct EqMatch {
    T v;
    inline bool operator()(T x) const { return x == v; }
};

template <typename T> struct RangeMatch {
    T lo, hi;
    inline bool operator()(T x) const { return x >= lo && x <= hi; }
};

// snap 非空时，把命中处的原始字节一并收走——这些字节此刻就在缓冲区里，
// 不额外产生任何系统调用，直接省掉建集时的 N 次 pread64。
template <typename T, typename M>
static void scan_typed(const std::vector<Region>& regions, size_t align, uint64_t limit,
                       M match, std::vector<uint64_t>& out, std::vector<uint8_t>* snap,
                       bool& truncated) {
    const size_t tsz = sizeof(T);
    if (align == 0) align = tsz;
    std::vector<uint8_t> buf(CHUNK_SIZE);
    truncated = false;

    for (size_t ri = 0; ri < regions.size(); ri++) {
        uint64_t addr = regions[ri].start;
        uint64_t end  = regions[ri].start + regions[ri].size;

        while (addr < end) {
            uint64_t remain = end - addr;
            size_t want = (remain < (uint64_t)CHUNK_SIZE) ? (size_t)remain : CHUNK_SIZE;
            ssize_t got = pread64(g_mem_fd, buf.data(), want, (off64_t)addr);
            if (got <= 0) { addr += MEM_PAGE; continue; }   // 该页不可读则跳过

            size_t g = (size_t)got;
            for (size_t off = 0; off + tsz <= g; off += align) {
                T v;
                memcpy(&v, buf.data() + off, tsz);
                if (match(v)) {
                    out.push_back(addr + off);
                    if (snap) snap->insert(snap->end(), buf.begin() + off, buf.begin() + off + tsz);
                    if ((uint64_t)out.size() >= limit) { truncated = true; return; }
                }
            }

            size_t advance = g;
            if (advance > tsz - 1) advance -= (tsz - 1);   // 重叠，防跨块漏检
            advance -= advance % align;                    // 保持对齐相位
            if (advance == 0) advance = align;
            addr += advance;
        }
    }
}

static void scan_aob(const std::vector<Region>& regions,
                     const std::vector<uint8_t>& pat, const std::vector<uint8_t>& mask,
                     uint64_t limit, std::vector<uint64_t>& out, std::vector<uint8_t>* snap,
                     bool& truncated) {
    const size_t plen = pat.size();
    std::vector<uint8_t> buf(CHUNK_SIZE);
    truncated = false;

    for (size_t ri = 0; ri < regions.size(); ri++) {
        uint64_t addr = regions[ri].start;
        uint64_t end  = regions[ri].start + regions[ri].size;

        while (addr < end) {
            uint64_t remain = end - addr;
            size_t want = (remain < (uint64_t)CHUNK_SIZE) ? (size_t)remain : CHUNK_SIZE;
            ssize_t got = pread64(g_mem_fd, buf.data(), want, (off64_t)addr);
            if (got <= 0) { addr += MEM_PAGE; continue; }

            size_t g = (size_t)got;
            if (g >= plen) {
                for (size_t off = 0; off + plen <= g; off++) {
                    bool hit = true;
                    for (size_t k = 0; k < plen; k++) {
                        if (mask[k] && buf[off + k] != pat[k]) { hit = false; break; }
                    }
                    if (hit) {
                        out.push_back(addr + off);
                        if (snap) snap->push_back(buf[off]);
                        if ((uint64_t)out.size() >= limit) { truncated = true; return; }
                    }
                }
            }

            size_t advance = g;
            if (advance > plen - 1) advance -= (plen - 1);
            if (advance == 0) advance = 1;
            addr += advance;
        }
    }
}

static bool dispatch_scan_exact(const std::string& type, const std::vector<uint8_t>& target,
                                const std::vector<Region>& regions, size_t align, uint64_t limit,
                                std::vector<uint64_t>& out, std::vector<uint8_t>* snap,
                                bool& truncated) {
    if (type == "byte") {
        int8_t v; memcpy(&v, target.data(), 1);
        EqMatch<int8_t> m; m.v = v;
        scan_typed<int8_t>(regions, align, limit, m, out, snap, truncated);
    } else if (type == "word") {
        int16_t v; memcpy(&v, target.data(), 2);
        EqMatch<int16_t> m; m.v = v;
        scan_typed<int16_t>(regions, align, limit, m, out, snap, truncated);
    } else if (type == "dword") {
        int32_t v; memcpy(&v, target.data(), 4);
        EqMatch<int32_t> m; m.v = v;
        scan_typed<int32_t>(regions, align, limit, m, out, snap, truncated);
    } else if (type == "qword") {
        int64_t v; memcpy(&v, target.data(), 8);
        EqMatch<int64_t> m; m.v = v;
        scan_typed<int64_t>(regions, align, limit, m, out, snap, truncated);
    } else if (type == "float") {
        float v; memcpy(&v, target.data(), 4);
        EqMatch<float> m; m.v = v;
        scan_typed<float>(regions, align, limit, m, out, snap, truncated);
    } else if (type == "double") {
        double v; memcpy(&v, target.data(), 8);
        EqMatch<double> m; m.v = v;
        scan_typed<double>(regions, align, limit, m, out, snap, truncated);
    } else {
        return false;
    }
    return true;
}

static bool dispatch_scan_range(const std::string& type, double lo, double hi,
                                const std::vector<Region>& regions, size_t align, uint64_t limit,
                                std::vector<uint64_t>& out, std::vector<uint8_t>* snap,
                                bool& truncated) {
    if (type == "byte") {
        RangeMatch<int8_t> m; m.lo = (int8_t)lo; m.hi = (int8_t)hi;
        scan_typed<int8_t>(regions, align, limit, m, out, snap, truncated);
    } else if (type == "word") {
        RangeMatch<int16_t> m; m.lo = (int16_t)lo; m.hi = (int16_t)hi;
        scan_typed<int16_t>(regions, align, limit, m, out, snap, truncated);
    } else if (type == "dword") {
        RangeMatch<int32_t> m; m.lo = (int32_t)lo; m.hi = (int32_t)hi;
        scan_typed<int32_t>(regions, align, limit, m, out, snap, truncated);
    } else if (type == "qword") {
        RangeMatch<int64_t> m; m.lo = (int64_t)lo; m.hi = (int64_t)hi;
        scan_typed<int64_t>(regions, align, limit, m, out, snap, truncated);
    } else if (type == "float") {
        RangeMatch<float> m; m.lo = (float)lo; m.hi = (float)hi;
        scan_typed<float>(regions, align, limit, m, out, snap, truncated);
    } else if (type == "double") {
        RangeMatch<double> m; m.lo = lo; m.hi = hi;
        scan_typed<double>(regions, align, limit, m, out, snap, truncated);
    } else {
        return false;
    }
    return true;
}

// 把 tsz 字节按 type 解释成 double，供统一比较
static double decode_as_double(const uint8_t* p, const std::string& type) {
    if (type == "byte")   { int8_t  v; memcpy(&v, p, 1); return (double)v; }
    if (type == "word")   { int16_t v; memcpy(&v, p, 2); return (double)v; }
    if (type == "dword")  { int32_t v; memcpy(&v, p, 4); return (double)v; }
    if (type == "qword")  { int64_t v; memcpy(&v, p, 8); return (double)v; }
    if (type == "float")  { float   v; memcpy(&v, p, 4); return (double)v; }
    if (type == "double") { double  v; memcpy(&v, p, 8); return v; }
    return 0.0;
}

// ==================== 命令处理 ====================

static void cmd_ping() {
    printf("{\"status\":\"ok\",\"proto\":%d,\"version\":\"%s\"}\n", PROTO_VERSION, SCANNER_VERSION);
    fflush(stdout);
}

static void cmd_search_exact(const char* line, int pid) {
    std::string type, target_hex;
    if (!json_str(line, "type", type))         return emit_error("missing type");
    if (!json_str(line, "target", target_hex)) return emit_error("missing target");

    size_t tsz = type_size_of(type);
    if (tsz == 0) return emit_error("bad type");

    std::vector<uint8_t> target;
    if (!hex_to_bytes(target_hex, target)) return emit_error("bad target hex");
    if (target.size() != tsz)              return emit_error("target size mismatch");

    std::vector<Region> regions;
    if (!parse_regions(line, regions)) return emit_error("missing regions");

    uint64_t limit = DEFAULT_LIMIT;
    json_u64(line, "limit", limit);
    if (limit == 0 || limit > MAX_LIMIT) limit = MAX_LIMIT;

    uint64_t align = 0;
    json_u64(line, "align", align);

    if (!ensure_mem(pid)) return emit_error("open mem failed");

    uint64_t t0 = now_ms();
    std::vector<uint64_t> out;
    std::vector<uint8_t>  snap;
    bool truncated = false;
    if (!dispatch_scan_exact(type, target, regions, (size_t)align, limit, out, &snap, truncated))
        return emit_error("unsupported type");

    size_t n = out.size();
    int id = store_set(out, type, tsz, &snap);
    emit_set_result(id, n, truncated, now_ms() - t0);
}

static void cmd_search_range(const char* line, int pid) {
    std::string type;
    if (!json_str(line, "type", type)) return emit_error("missing type");
    size_t tsz = type_size_of(type);
    if (tsz == 0) return emit_error("bad type");

    double lo = 0, hi = 0;
    if (!json_double(line, "low", lo))  return emit_error("missing low");
    if (!json_double(line, "high", hi)) return emit_error("missing high");
    if (lo > hi) return emit_error("low greater than high");

    std::vector<Region> regions;
    if (!parse_regions(line, regions)) return emit_error("missing regions");

    uint64_t limit = DEFAULT_LIMIT;
    json_u64(line, "limit", limit);
    if (limit == 0 || limit > MAX_LIMIT) limit = MAX_LIMIT;

    uint64_t align = 0;
    json_u64(line, "align", align);

    if (!ensure_mem(pid)) return emit_error("open mem failed");

    uint64_t t0 = now_ms();
    std::vector<uint64_t> out;
    std::vector<uint8_t>  snap;
    bool truncated = false;
    if (!dispatch_scan_range(type, lo, hi, regions, (size_t)align, limit, out, &snap, truncated))
        return emit_error("unsupported type");

    size_t n = out.size();
    int id = store_set(out, type, tsz, &snap);
    emit_set_result(id, n, truncated, now_ms() - t0);
}

static void cmd_search_aob(const char* line, int pid) {
    std::string pat_hex, mask_hex;
    if (!json_str(line, "pattern", pat_hex)) return emit_error("missing pattern");
    if (!json_str(line, "mask", mask_hex))   return emit_error("missing mask");

    std::vector<uint8_t> pat, mask;
    if (!hex_to_bytes(pat_hex, pat))   return emit_error("bad pattern hex");
    if (!hex_to_bytes(mask_hex, mask)) return emit_error("bad mask hex");
    if (pat.empty())                   return emit_error("empty pattern");
    if (mask.size() != pat.size())     return emit_error("mask length mismatch");

    std::vector<Region> regions;
    if (!parse_regions(line, regions)) return emit_error("missing regions");

    uint64_t limit = DEFAULT_LIMIT;
    json_u64(line, "limit", limit);
    if (limit == 0 || limit > MAX_LIMIT) limit = MAX_LIMIT;

    if (!ensure_mem(pid)) return emit_error("open mem failed");

    uint64_t t0 = now_ms();
    std::vector<uint64_t> out;
    std::vector<uint8_t>  snap;
    bool truncated = false;
    scan_aob(regions, pat, mask, limit, out, &snap, truncated);

    size_t n = out.size();
    int id = store_set(out, "byte", 1, &snap);
    emit_set_result(id, n, truncated, now_ms() - t0);
}

static void cmd_refine_value(const char* line, int pid) {
    int64_t sid = 0;
    if (!json_i64(line, "set", sid)) return emit_error("missing set");
    std::map<int, ResultSet>::iterator it = g_sets.find((int)sid);
    if (it == g_sets.end()) return emit_error("set not found");

    std::string type = it->second.type, target_hex;
    json_str(line, "type", type);
    if (!json_str(line, "target", target_hex)) return emit_error("missing target");

    size_t tsz = type_size_of(type);
    if (tsz == 0) return emit_error("bad type");

    std::vector<uint8_t> target;
    if (!hex_to_bytes(target_hex, target)) return emit_error("bad target hex");
    if (target.size() != tsz)              return emit_error("target size mismatch");

    if (!ensure_mem(pid)) return emit_error("open mem failed");

    uint64_t t0 = now_ms();
    std::vector<uint64_t> in = it->second.addrs;   // 复制：store_set 会改动 g_sets
    std::vector<uint8_t> vals, ok;
    read_values(in, tsz, vals, ok);

    std::vector<uint64_t> out;
    std::vector<uint8_t>  snap;
    for (size_t i = 0; i < in.size(); i++) {
        if (!ok[i]) continue;
        if (memcmp(&vals[i * tsz], target.data(), tsz) == 0) {
            out.push_back(in[i]);
            snap.insert(snap.end(), vals.begin() + i * tsz, vals.begin() + (i + 1) * tsz);
        }
    }

    size_t n = out.size();
    int id = store_set(out, type, tsz, &snap);
    emit_set_result(id, n, false, now_ms() - t0);
}

static void cmd_refine_range(const char* line, int pid) {
    int64_t sid = 0;
    if (!json_i64(line, "set", sid)) return emit_error("missing set");
    std::map<int, ResultSet>::iterator it = g_sets.find((int)sid);
    if (it == g_sets.end()) return emit_error("set not found");

    std::string type = it->second.type;
    json_str(line, "type", type);
    size_t tsz = type_size_of(type);
    if (tsz == 0) return emit_error("bad type");

    double lo = 0, hi = 0;
    if (!json_double(line, "low", lo))  return emit_error("missing low");
    if (!json_double(line, "high", hi)) return emit_error("missing high");
    if (lo > hi) return emit_error("low greater than high");

    if (!ensure_mem(pid)) return emit_error("open mem failed");

    uint64_t t0 = now_ms();
    std::vector<uint64_t> in = it->second.addrs;
    std::vector<uint8_t> vals, ok;
    read_values(in, tsz, vals, ok);

    std::vector<uint64_t> out;
    std::vector<uint8_t>  snap;
    for (size_t i = 0; i < in.size(); i++) {
        if (!ok[i]) continue;
        double v = decode_as_double(&vals[i * tsz], type);
        if (v >= lo && v <= hi) {
            out.push_back(in[i]);
            snap.insert(snap.end(), vals.begin() + i * tsz, vals.begin() + (i + 1) * tsz);
        }
    }

    size_t n = out.size();
    int id = store_set(out, type, tsz, &snap);
    emit_set_result(id, n, false, now_ms() - t0);
}

// mode: 0=changed 1=unchanged 2=increased 3=decreased
static void cmd_refine_fuzzy(const char* line, int pid) {
    int64_t sid = 0, mode = 0;
    if (!json_i64(line, "set", sid))   return emit_error("missing set");
    if (!json_i64(line, "mode", mode)) return emit_error("missing mode");
    if (mode < 0 || mode > 3)          return emit_error("bad mode");

    std::map<int, ResultSet>::iterator it = g_sets.find((int)sid);
    if (it == g_sets.end()) return emit_error("set not found");

    std::string type = it->second.type;
    size_t tsz = it->second.type_size;
    if (tsz == 0) return emit_error("bad type");
    if (it->second.snapshot.size() != it->second.addrs.size() * tsz)
        return emit_error("snapshot missing");

    if (!ensure_mem(pid)) return emit_error("open mem failed");

    uint64_t t0 = now_ms();
    std::vector<uint64_t> in  = it->second.addrs;
    std::vector<uint8_t>  old = it->second.snapshot;
    std::vector<uint8_t> vals, ok;
    read_values(in, tsz, vals, ok);

    std::vector<uint64_t> out;
    std::vector<uint8_t>  snap;
    for (size_t i = 0; i < in.size(); i++) {
        if (!ok[i]) continue;
        double cur = decode_as_double(&vals[i * tsz], type);
        double prv = decode_as_double(&old[i * tsz], type);
        bool hit = false;
        switch (mode) {
            case 0: hit = (cur != prv); break;
            case 1: hit = (cur == prv); break;
            case 2: hit = (cur >  prv); break;
            case 3: hit = (cur <  prv); break;
        }
        if (hit) {
            out.push_back(in[i]);
            snap.insert(snap.end(), vals.begin() + i * tsz, vals.begin() + (i + 1) * tsz);
        }
    }

    size_t n = out.size();
    int id = store_set(out, type, tsz, &snap);
    emit_set_result(id, n, false, now_ms() - t0);
}

static void cmd_snapshot(const char* line, int pid) {
    int64_t sid = 0;
    if (!json_i64(line, "set", sid)) return emit_error("missing set");
    std::map<int, ResultSet>::iterator it = g_sets.find((int)sid);
    if (it == g_sets.end()) return emit_error("set not found");
    if (!ensure_mem(pid)) return emit_error("open mem failed");

    std::vector<uint8_t> ok;
    read_values(it->second.addrs, it->second.type_size, it->second.snapshot, ok);
    printf("{\"status\":\"ok\",\"count\":%zu}\n", it->second.addrs.size());
    fflush(stdout);
}

static void cmd_get_results(const char* line, int pid) {
    int64_t sid = 0;
    if (!json_i64(line, "set", sid)) return emit_error("missing set");
    std::map<int, ResultSet>::iterator it = g_sets.find((int)sid);
    if (it == g_sets.end()) return emit_error("set not found");

    uint64_t offset = 0, limit = 100, want_mc = 0;
    json_u64(line, "offset", offset);
    json_u64(line, "limit", limit);
    json_u64(line, "mc", want_mc);
    if (limit == 0 || limit > MAX_PAGE_LIMIT) limit = MAX_PAGE_LIMIT;

    ResultSet& rs = it->second;
    if (!ensure_mem(pid)) return emit_error("open mem failed");

    size_t total = rs.addrs.size();
    size_t start = (offset > (uint64_t)total) ? total : (size_t)offset;
    size_t stop  = start + (size_t)limit;
    if (stop > total) stop = total;

    size_t tsz = rs.type_size;
    printf("{\"status\":\"ok\",\"set\":%d,\"count\":%zu,\"offset\":%zu,\"type\":\"%s\",\"items\":[",
           (int)sid, total, start, rs.type.c_str());

    std::string vhex, mhex;
    uint8_t vbuf[8], mbuf[8];
    for (size_t i = start; i < stop; i++) {
        if (i > start) printf(",");
        uint64_t a = rs.addrs[i];
        ssize_t got = pread64(g_mem_fd, vbuf, tsz, (off64_t)a);
        if (got == (ssize_t)tsz) bytes_to_hex(vbuf, tsz, vhex); else vhex.clear();
        printf("{\"a\":\"%" PRIx64 "\",\"v\":\"%s\"", a, vhex.c_str());
        if (want_mc) {
            ssize_t g2 = pread64(g_mem_fd, mbuf, 8, (off64_t)a);
            if (g2 == 8) bytes_to_hex(mbuf, 8, mhex); else mhex.clear();
            printf(",\"m\":\"%s\"", mhex.c_str());
        }
        printf("}");
    }
    printf("]}\n");
    fflush(stdout);
}

static void cmd_clear_set(const char* line) {
    int64_t sid = 0;
    if (!json_i64(line, "set", sid)) return emit_error("missing set");
    if (sid < 0) g_sets.clear();
    else         g_sets.erase((int)sid);
    printf("{\"status\":\"ok\",\"sets\":%zu}\n", g_sets.size());
    fflush(stdout);
}

static void cmd_list_sets() {
    printf("{\"status\":\"ok\",\"sets\":[");
    bool first = true;
    for (std::map<int, ResultSet>::iterator it = g_sets.begin(); it != g_sets.end(); ++it) {
        if (!first) printf(",");
        first = false;
        printf("{\"id\":%d,\"count\":%zu,\"type\":\"%s\"}",
               it->first, it->second.addrs.size(), it->second.type.c_str());
    }
    printf("]}\n");
    fflush(stdout);
}

static void cmd_read(const char* line, int pid) {
    uint64_t addr = 0, size = 0;
    if (!json_u64(line, "addr", addr)) return emit_error("missing addr");
    if (!json_u64(line, "size", size)) return emit_error("missing size");
    if (size == 0 || size > MAX_READ_SIZE) return emit_error("bad size");
    if (!ensure_mem(pid)) return emit_error("open mem failed");

    std::vector<uint8_t> buf((size_t)size);
    ssize_t got = pread64(g_mem_fd, buf.data(), (size_t)size, (off64_t)addr);
    if (got <= 0) return emit_error("read failed");

    std::string hex;
    bytes_to_hex(buf.data(), (size_t)got, hex);
    printf("{\"status\":\"ok\",\"size\":%zd,\"data\":\"%s\"}\n", got, hex.c_str());
    fflush(stdout);
}

static void cmd_read_many(const char* line, int pid) {
    uint64_t size = 0;
    if (!json_u64(line, "size", size)) return emit_error("missing size");
    if (size == 0 || size > 4096) return emit_error("bad size");

    std::vector<uint64_t> addrs;
    if (!parse_addr_array(line, "addrs", addrs, MAX_READ_MANY)) return emit_error("bad addrs");
    if (addrs.empty()) return emit_error("empty addrs");
    if (!ensure_mem(pid)) return emit_error("open mem failed");

    printf("{\"status\":\"ok\",\"data\":[");
    std::vector<uint8_t> buf((size_t)size);
    std::string hex;
    for (size_t i = 0; i < addrs.size(); i++) {
        if (i) printf(",");
        ssize_t got = pread64(g_mem_fd, buf.data(), (size_t)size, (off64_t)addrs[i]);
        if (got == (ssize_t)size) bytes_to_hex(buf.data(), (size_t)size, hex); else hex.clear();
        printf("\"%s\"", hex.c_str());
    }
    printf("]}\n");
    fflush(stdout);
}

static void cmd_write(const char* line, int pid) {
    uint64_t addr = 0;
    std::string data_hex;
    if (!json_u64(line, "addr", addr))     return emit_error("missing addr");
    if (!json_str(line, "data", data_hex)) return emit_error("missing data");

    std::vector<uint8_t> data;
    if (!hex_to_bytes(data_hex, data)) return emit_error("bad data hex");
    if (data.empty() || data.size() > MAX_READ_SIZE) return emit_error("bad data size");
    if (!ensure_mem(pid)) return emit_error("open mem failed");

    ssize_t wr = pwrite64(g_mem_fd, data.data(), data.size(), (off64_t)addr);
    if (wr != (ssize_t)data.size()) return emit_error("write failed");

    printf("{\"status\":\"ok\",\"written\":%zd}\n", wr);
    fflush(stdout);
}

// ==================== 分派 ====================

static void dispatch(const char* line) {
    std::string cmd;
    if (!json_str(line, "cmd", cmd)) { emit_error("missing cmd"); return; }

    if (cmd == "ping")      { cmd_ping();          return; }
    if (cmd == "list_sets") { cmd_list_sets();     return; }
    if (cmd == "clear_set") { cmd_clear_set(line); return; }

    // 以下命令都需要 pid
    int64_t pid64 = 0;
    if (!json_i64(line, "pid", pid64) || pid64 <= 0) { emit_error("missing pid"); return; }
    int pid = (int)pid64;

    if      (cmd == "search_exact") cmd_search_exact(line, pid);
    else if (cmd == "search_range") cmd_search_range(line, pid);
    else if (cmd == "search_aob")   cmd_search_aob(line, pid);
    else if (cmd == "refine_value") cmd_refine_value(line, pid);
    else if (cmd == "refine_range") cmd_refine_range(line, pid);
    else if (cmd == "refine_fuzzy") cmd_refine_fuzzy(line, pid);
    else if (cmd == "snapshot")     cmd_snapshot(line, pid);
    else if (cmd == "get_results")  cmd_get_results(line, pid);
    else if (cmd == "read")         cmd_read(line, pid);
    else if (cmd == "read_many")    cmd_read_many(line, pid);
    else if (cmd == "write")        cmd_write(line, pid);
    else                            emit_error("unknown cmd");
}

int main() {
    char*   line = nullptr;
    size_t  cap  = 0;
    ssize_t len;

    // 动态读行：模糊过滤等命令的参数长度可能远超固定缓冲
    while ((len = getline(&line, &cap, stdin)) > 0) {
        while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) line[--len] = 0;
        if (len == 0) continue;
        dispatch(line);
    }

    free(line);
    if (g_mem_fd >= 0) close(g_mem_fd);
    return 0;
}
