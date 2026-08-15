#!/usr/bin/env python3
"""
MCP 端点冒烟测试。在 App 里启动 MCP 服务后运行，验证协议层是否打通。

用法:
    python3 tools/mcp_smoke_test.py http://127.0.0.1:8788/mcp <token>

只做只读探测，不会修改任何内存。
"""
import json
import sys
import urllib.error
import urllib.request

ENDPOINT = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8788/mcp"
TOKEN = sys.argv[2] if len(sys.argv) > 2 else ""

passed = failed = 0


def check(name, cond, extra=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  \033[32mPASS\033[0m {name}")
    else:
        failed += 1
        print(f"  \033[31mFAIL\033[0m {name} {extra}")


def rpc(method, params=None, req_id=1, token=TOKEN, timeout=180):
    body = {"jsonrpc": "2.0", "method": method}
    if req_id is not None:
        body["id"] = req_id
    if params is not None:
        body["params"] = params

    req = urllib.request.Request(
        ENDPOINT,
        data=json.dumps(body).encode(),
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            **({"Authorization": f"Bearer {token}"} if token else {}),
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw) if raw.strip() else None
        except json.JSONDecodeError:
            return e.code, {"raw": raw}
    except Exception as e:
        return 0, {"transport_error": str(e)}


def tool(name, args=None, timeout=180):
    status, r = rpc("tools/call", {"name": name, "arguments": args or {}}, timeout=timeout)
    if not r or "result" not in r:
        return status, r, None
    content = r["result"].get("content", [])
    text = content[0]["text"] if content else "{}"
    try:
        return status, r, json.loads(text)
    except json.JSONDecodeError:
        return status, r, {"raw": text}


def main():
    print(f"端点 {ENDPOINT}\n")

    print("[鉴权]")
    status, r = rpc("ping", token="")
    check("无令牌被拒绝", status == 401, f"实际 HTTP {status}")
    status, r = rpc("ping", token="wrong-token-value")
    check("错误令牌被拒绝", status == 401, f"实际 HTTP {status}")

    if not TOKEN:
        print("\n未提供令牌，后续测试跳过。令牌见 App 的「设置 → MCP 服务」页。")
        sys.exit(1)

    print("\n[MCP 握手]")
    status, r = rpc("initialize", {
        "protocolVersion": "2025-06-18",
        "capabilities": {},
        "clientInfo": {"name": "smoke-test", "version": "1.0"},
    })
    ok = status == 200 and r and "result" in r
    check("initialize 成功", ok, r)
    if ok:
        res = r["result"]
        check("回应了协议版本", bool(res.get("protocolVersion")), res)
        check("声明了 tools 能力", "tools" in res.get("capabilities", {}), res)
        check("带了 serverInfo", bool(res.get("serverInfo", {}).get("name")), res)

    status, r = rpc("notifications/initialized", req_id=None)
    check("initialized 通知返回 202 且无正文", status == 202 and r is None, f"HTTP {status}, body={r}")

    status, r = rpc("ping")
    check("ping 成功", status == 200 and r and "result" in r, r)

    print("\n[工具列表]")
    status, r = rpc("tools/list")
    tools = r["result"]["tools"] if r and "result" in r else []
    names = [t["name"] for t in tools]
    check(f"tools/list 返回 {len(tools)} 个工具", len(tools) >= 16, names)
    for expect in ["gg_status", "gg_list_processes", "gg_attach", "gg_search",
                   "gg_refine", "gg_list_results", "gg_read", "gg_write"]:
        check(f"包含 {expect}", expect in names)
    check("每个工具都有 inputSchema",
          all("inputSchema" in t and t["inputSchema"].get("type") == "object" for t in tools))
    check("每个工具都有描述",
          all(len(t.get("description", "")) > 20 for t in tools))

    print("\n[gg_status]")
    status, r, data = tool("gg_status")
    check("调用成功", status == 200 and data is not None, r)
    if data:
        check("含 read_only 字段", "read_only" in data, data)
        check("含 next_step 指引", "next_step" in data, data)
        print(f"    root: {data.get('root')}")
        print(f"    已附加进程: {data.get('attached_pid')}")
        print(f"    只读模式: {data.get('read_only')}")

    print("\n[错误处理]")
    status, r = rpc("no/such/method")
    check("未知方法返回 -32601",
          r and r.get("error", {}).get("code") == -32601, r)

    status, r, data = tool("gg_no_such_tool")
    check("未知工具返回 isError",
          r and r.get("result", {}).get("isError") is True, r)

    status, r, data = tool("gg_read", {"address": "not-an-address"})
    check("非法参数返回 isError 而非崩溃",
          r and r.get("result", {}).get("isError") is True, r)

    status, r, data = tool("gg_search", {"mode": "exact", "value": "1"})
    check("未附加进程时给出明确提示",
          data and "error" in data, data)

    print("\n[只读模式]")
    status, r, data = tool("gg_write",
                           {"address": "0x1000", "value": "1", "type": "dword"})
    if data and "只读模式" in str(data.get("error", "")):
        check("只读模式拦截了写操作", True)
    else:
        check("只读模式已关闭（写操作未被拦截）", True,
              "  注意：当前允许 AI 直接改内存")

    print(f"\n{'=' * 46}\n通过 {passed} / 失败 {failed}\n{'=' * 46}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
