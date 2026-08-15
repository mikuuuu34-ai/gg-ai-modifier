#!/usr/bin/env python3
"""
scanner_root v2 协议验证：起一个靶子进程，用真实内存读写跑完整流程。
重点验证 v1 的两个致命问题是否修掉：
  - byte/word 搜索曾经静默无输出 → 调用方永久阻塞
  - MAX_RESULTS 500 硬截断且不报告
用法: python3 test_scanner.py <scanner可执行文件路径>
"""
import json
import os
import re
import struct
import subprocess
import sys
import threading
import queue

SCANNER = sys.argv[1] if len(sys.argv) > 1 else "/tmp/ggtest/scanner_root"
HERE = os.path.dirname(os.path.abspath(__file__))

_FMT = {"byte": "<b", "word": "<h", "dword": "<i", "qword": "<q",
        "float": "<f", "double": "<d"}


def le(typ, val):
    """按类型打成小端十六进制，避免手算出错"""
    return struct.pack(_FMT[typ], val).hex()


passed = failed = 0


def check(name, cond, extra=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  \033[32mPASS\033[0m {name}")
    else:
        failed += 1
        print(f"  \033[31mFAIL\033[0m {name} {extra}")


class Scanner:
    """带超时的行式 JSON 客户端。超时即判定为挂死。"""

    def __init__(self, path):
        self.p = subprocess.Popen(
            [path], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, text=True, bufsize=1)
        self.q = queue.Queue()
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        for line in self.p.stdout:
            self.q.put(line.strip())

    def cmd(self, obj, timeout=30):
        self.p.stdin.write(json.dumps(obj) + "\n")
        self.p.stdin.flush()
        try:
            return json.loads(self.q.get(timeout=timeout))
        except queue.Empty:
            return {"status": "TIMEOUT"}
        except json.JSONDecodeError as e:
            return {"status": "BADJSON", "msg": str(e)}

    def close(self):
        try:
            self.p.stdin.close()
            self.p.wait(timeout=5)
        except Exception:
            self.p.kill()


def read_regions(pid):
    """模仿 MemoryEngine.getRegions：只取可读可写、非巨型的段"""
    regions = []
    with open(f"/proc/{pid}/maps") as f:
        for line in f:
            parts = line.split()
            if len(parts) < 2:
                continue
            rng, perms = parts[0], parts[1]
            if "r" not in perms or "w" not in perms:
                continue
            a, b = rng.split("-")
            start, end = int(a, 16), int(b, 16)
            size = end - start
            if size <= 0 or size > 100 * 1024 * 1024:
                continue
            regions.append({"start": start, "size": size})
    return regions


def main():
    global failed
    # 1. 编译并启动靶子
    subprocess.run(["gcc", "-O0", f"{HERE}/victim.c", "-o", f"{HERE}/victim"], check=True)
    victim = subprocess.Popen([f"{HERE}/victim"], stdin=subprocess.PIPE,
                              stdout=subprocess.PIPE, text=True, bufsize=1)
    info = victim.stdout.readline().strip()
    m = dict(re.findall(r"(\w+)=(\S+)", info))
    pid = int(m["pid"])
    addr_int = int(m["int"], 16)
    addr_float = int(m["float"], 16)
    addr_word = int(m["word"], 16)
    addr_byte = int(m["byte"], 16)
    addr_qword = int(m["qword"], 16)
    print(f"靶子 pid={pid} int@0x{addr_int:x}")

    regions = read_regions(pid)
    print(f"可扫描段 {len(regions)} 个\n")

    sc = Scanner(SCANNER)

    def addrs_of(setid, max_scan=20000):
        """翻页收集地址，避免目标落在第一页之外造成误判"""
        out, off = [], 0
        while len(out) < max_scan:
            r = sc.cmd({"cmd": "get_results", "pid": pid, "set": setid,
                        "offset": off, "limit": 1000})
            if r.get("status") != "ok":
                return out, r
            items = r["items"]
            out.extend(int(i["a"], 16) for i in items)
            off += len(items)
            if not items or off >= r["count"]:
                break
        return out, r

    print("[基础协议]")
    r = sc.cmd({"cmd": "ping"})
    check("ping", r.get("status") == "ok" and r.get("proto") == 2, r)

    print("\n[各类型精确搜索 — v1 里 byte/word/qword 会静默挂死]")
    cases = [
        ("dword",  le("dword", 12345678),        addr_int,   12345678),
        ("float",  le("float", 3.5),             addr_float, 3.5),
        ("word",   le("word", 4242),             addr_word,  4242),
        ("byte",   le("byte", 77),               addr_byte,  77),
        ("qword",  le("qword", 1234567890123),   addr_qword, 1234567890123),
    ]
    setids = {}
    for typ, target_hex, want_addr, val in cases:
        r = sc.cmd({"cmd": "search_exact", "pid": pid, "type": typ,
                    "target": target_hex, "regions": regions, "limit": 100000}, timeout=60)
        if r.get("status") != "ok":
            check(f"search {typ}={val}", False, r)
            continue
        setids[typ] = r["set"]
        found, _ = addrs_of(r["set"])
        check(f"search {typ}={val} 命中已知地址 (count={r['count']})",
              want_addr in found, f"want 0x{want_addr:x}，已查 {len(found)} 条未见")

    print("\n[截断如实上报]")
    r = sc.cmd({"cmd": "search_exact", "pid": pid, "type": "dword",
                "target": le("dword", 0), "regions": regions, "limit": 5}, timeout=120)
    check("limit=5 时 truncated=true", r.get("status") == "ok" and r.get("count") == 5
          and r.get("truncated") is True, r)
    r2 = sc.cmd({"cmd": "search_exact", "pid": pid, "type": "dword",
                 "target": le("dword", 0), "regions": regions, "limit": 2000000}, timeout=120)
    check(f"放开 limit 后结果远超 500 (count={r2.get('count')})",
          r2.get("status") == "ok" and r2.get("count", 0) > 500, r2)

    print("\n[二次过滤]")
    base = setids.get("dword")
    victim.stdin.write("set 999\n"); victim.stdin.flush(); victim.stdout.readline()
    r = sc.cmd({"cmd": "refine_value", "pid": pid, "set": base,
                "type": "dword", "target": le("dword", 999)}, timeout=60)
    ok = r.get("status") == "ok"
    found, _ = addrs_of(r["set"]) if ok else ([], None)
    check("改值后 refine_value 仍锁定同一地址", ok and addr_int in found, r)
    check("过滤后结果数变少", ok and r["count"] < 100000, r)

    print("\n[模糊比对]")
    victim.stdin.write("set 12345678\n"); victim.stdin.flush(); victim.stdout.readline()
    r = sc.cmd({"cmd": "search_exact", "pid": pid, "type": "dword",
                "target": le("dword", 12345678), "regions": regions, "limit": 100000}, timeout=60)
    fset = r["set"]
    victim.stdin.write("set 22345678\n"); victim.stdin.flush(); victim.stdout.readline()
    r = sc.cmd({"cmd": "refine_fuzzy", "pid": pid, "set": fset, "mode": 2}, timeout=60)  # increased
    found, _ = addrs_of(r["set"]) if r.get("status") == "ok" else ([], None)
    check("refine_fuzzy(increased) 命中变大的地址", addr_int in found, r)

    r = sc.cmd({"cmd": "refine_fuzzy", "pid": pid, "set": fset, "mode": 3}, timeout=60)  # decreased
    found2, _ = addrs_of(r["set"]) if r.get("status") == "ok" else ([], None)
    check("refine_fuzzy(decreased) 不命中变大的地址", addr_int not in found2, r)

    print("\n[读写]")
    r = sc.cmd({"cmd": "read", "pid": pid, "addr": addr_int, "size": 4})
    check("read 读回当前值 22345678", r.get("data") == le("dword", 22345678), r)

    r = sc.cmd({"cmd": "read_many", "pid": pid,
                "addrs": [f"{addr_int:x}", f"{addr_word:x}"], "size": 2})
    check("read_many 批量读", r.get("status") == "ok" and len(r.get("data", [])) == 2, r)

    r = sc.cmd({"cmd": "write", "pid": pid, "addr": addr_int, "data": le("dword", 123456789)})
    victim.stdin.write("get\n"); victim.stdin.flush()
    got = victim.stdout.readline().strip()
    check("write 后靶子读到 123456789", got == "val 123456789", f"靶子回报 {got!r}")

    print("\n[错误路径必须回错误而不是挂死 — v1 的核心缺陷]")
    for name, c in [
        ("未知命令", {"cmd": "no_such_cmd", "pid": pid}),
        ("缺 pid", {"cmd": "read", "addr": 100, "size": 4}),
        ("非法类型", {"cmd": "search_exact", "pid": pid, "type": "int128",
                      "target": "00", "regions": regions}),
        ("target 长度不符", {"cmd": "search_exact", "pid": pid, "type": "dword",
                             "target": "00", "regions": regions}),
        ("缺 regions", {"cmd": "search_exact", "pid": pid, "type": "dword", "target": "00000000"}),
        ("结果集不存在", {"cmd": "get_results", "pid": pid, "set": 99999}),
        ("size 越界", {"cmd": "read", "pid": pid, "addr": addr_int, "size": 999999999}),
        ("缺 addr 字段", {"cmd": "read", "pid": pid}),
        ("奇数长度 hex", {"cmd": "write", "pid": pid, "addr": addr_int, "data": "abc"}),
    ]:
        r = sc.cmd(c, timeout=10)
        check(f"{name} → error", r.get("status") == "error", r)

    r = sc.cmd({"cmd": "ping"}, timeout=10)
    check("连续错误后连接仍可用", r.get("status") == "ok", r)

    print("\n[结果集管理]")
    r = sc.cmd({"cmd": "list_sets"})
    check("list_sets 可用", r.get("status") == "ok" and isinstance(r.get("sets"), list), r)
    check(f"结果集数量受上限约束 ({len(r.get('sets', []))} <= 8)", len(r.get("sets", [])) <= 8, r)
    r = sc.cmd({"cmd": "clear_set", "set": -1})
    check("clear_set(-1) 清空全部", r.get("status") == "ok" and r.get("sets") == 0, r)

    sc.close()
    try:
        victim.stdin.write("q\n"); victim.stdin.flush()
        victim.wait(timeout=5)
    except Exception:
        victim.kill()

    print(f"\n{'='*46}\n通过 {passed} / 失败 {failed}\n{'='*46}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
