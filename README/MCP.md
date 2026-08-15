# MCP 服务：让 Claude Code / Codex 接管内存修改

把 App 的内存操作能力开放成 MCP 接口，由外部 AI 客户端负责推理和收敛，App 只负责以 root 执行。

内存搜索本身就是「搜索 → 让数值变化 → 再过滤 → 收敛 → 验证」的多轮迭代，
比起一次性的 Function Calling，交给成熟 agent 的循环更合适。

App 内置的 AI 对话功能保持不变，两者可以并存，共用同一套内存引擎。

---

## 快速开始

### 1. 启动服务

App 内进入 **设置 → 🔌 MCP 服务 → 启动服务**。

页面会显示：

- **本机地址** `http://127.0.0.1:8788/mcp`
- **访问令牌** 随机生成，可点眼睛图标显示、点复制图标拷贝
- **连接命令** 一键复制完整的 `claude mcp add` 命令

### 2. 接入客户端

复制页面上的连接命令直接执行即可，等价于：

```bash
claude mcp add --transport http gg-modifier \
  http://127.0.0.1:8788/mcp \
  --header "Authorization: Bearer <令牌>"
```

其他 MCP 客户端手动配置时：

| 项 | 值 |
|---|---|
| 传输方式 | Streamable HTTP |
| 地址 | `http://127.0.0.1:8788/mcp` |
| 请求头 | `Authorization: Bearer <令牌>` |

若客户端不便设置请求头，也可以退回到查询参数 `?token=<令牌>`（会出现在日志里，不推荐）。

### 3. 让 AI 干活

```
先看看 gg_status，然后列出正在跑的进程，附加到我的单机游戏上
```

```
帮我找到金币数值。当前显示 1500。
找到后先别改，告诉我候选地址有几个。
```

典型收敛流程（AI 会自己走完）：

```
gg_attach          附加到游戏进程
gg_search          搜 1500 → 命中 3 万个地址，返回 set_id
                   （回到游戏花掉一些金币，变成 1200）
gg_refine          按新值 1200 过滤 → 剩 7 个
gg_list_results    查看这 7 个地址
gg_write           改成目标值，自动回读校验
gg_freeze          需要的话锁住，防止游戏改回去
```

---

## 为什么搜索不直接返回地址列表

一次首轮搜索命中几十万个地址是常态。把它们塞进对话上下文会立刻把窗口撑爆，
而且对推理毫无帮助——agent 需要的是「还剩多少个」和「下一步怎么缩小」，
不是十万个十六进制数。

所以搜索只返回：

```json
{
  "set_id": 3,
  "count": 31402,
  "truncated": false,
  "elapsed_ms": 1840,
  "sample": [ ... 前 8 条 ... ],
  "next_step": "还有 31402 条。回到游戏让目标数值发生变化，再用 gg_refine 继续收敛"
}
```

完整结果集留在扫描器进程内。需要查看时用 `gg_list_results` 分页取，
但正确做法是先用 `gg_refine` 收敛到几十条以内再看。

---

## 工具一览

| 工具 | 用途 |
|---|---|
| `gg_status` | 服务/root/附加状态。**不需要 root，可用来确认链路是否打通** |
| `gg_list_processes` | 列出运行中的应用进程 |
| `gg_attach` / `gg_detach` | 按 pid 或包名附加/解除 |
| `gg_list_regions` | 查看可扫描内存段（`[heap]`、`[anon:*]` 优先） |
| `gg_search` | 精确 / 区间 / 特征码搜索，返回 set_id |
| `gg_refine` | 在结果集上二次过滤：按值、按区间、按增减变化 |
| `gg_list_results` | 分页查看结果集及当前值 |
| `gg_clear_results` | 释放结果集 |
| `gg_read` | 读单个地址，不指定类型时按六种类型各解释一遍 |
| `gg_read_many` | 一次读多个候选地址，便于观察它们同时的变化 |
| `gg_read_bytes` | 十六进制 + ASCII 转储，看结构体布局或字符串 |
| `gg_write` | 写入并自动回读校验，返回写前/写后值 |
| `gg_write_batch` | 批量写入 |
| `gg_freeze` | 冻结/解冻，后台每 100ms 写回 |
| `gg_run_lua` | 执行 GG 兼容的 Lua 脚本（无人值守模式） |

数据类型：`byte` / `word` / `dword` / `qword` / `float` / `double`。
血量、金币这类整数通常是 `dword`，坐标、速度通常是 `float`。

---

## 安全

这是一个能以 root 读写任意进程内存的 HTTP 接口，默认配置刻意收紧：

| 项 | 默认 | 说明 |
|---|---|---|
| 绑定地址 | `127.0.0.1` | 只有本机能连。开启「允许局域网访问」后才监听所有网卡 |
| 访问令牌 | 随机生成，强制校验 | 比较用常量时间，避免泄漏前缀长度 |
| 只读模式 | **开启** | `gg_write` / `gg_write_batch` / `gg_freeze` / `gg_run_lua` 一律拒绝 |

只读模式是一个总开关而不是逐次弹窗确认——agent 一轮可能写几十个地址，
逐次确认会让它完全没法用。想让 AI 动手时，在设置页手动关掉即可。

**调用记录**：设置页会列出最近 100 次工具调用（工具名、成功与否、耗时），
可以回看 AI 到底做了什么。

令牌存在 App 私有的 SharedPreferences 里，不进仓库。可随时重新生成使旧令牌作废。

---

## 已知限制

- **大范围扫描耗时**：全量内存扫描可能持续数十秒。客户端侧建议调大 MCP 超时
  （Claude Code 可用 `MCP_TOOL_TIMEOUT` 环境变量）。
- **`gg_run_lua` 是无人值守模式**：`gg.choice` / `gg.prompt` / `gg.alert` 不会弹窗，
  而是立即返回默认值并在输出里标注。依赖交互分支的脚本行为会与手动执行不同。
- **Lua 里的搜索只能看到前 500 条**：`gg.searchNumber` 走的是给界面用的旧同步接口，
  单页上限 500，`gg.getResultsCount()` 返回的也是这个数而非真实命中数，
  后续 `gg.refineNumber` 同样只在这 500 条里过滤。
  这是 Lua 桥接层的固有限制（旧版扫描器本来就硬截断在 500，可见范围没变）。
  需要完整结果集请改用 `gg_search` + `gg_refine`。
- **未知初值的模糊搜索**：首轮建议用 `gg_search mode=range` 圈定一个合理区间
  （例如血量 0~10000），再配合 `gg_refine mode=fuzzy` 按增减收敛。
  直接对全类型值域做首轮模糊搜索会命中过多并触发截断。
- **`truncated` 字段要看**：命中数达到 limit 时扫描会提前停止，
  目标可能不在结果集里。此时应缩小条件或调大 `limit` 重搜。
- 书签工具未提供：书签存在 Dart 侧的 Hive 里，原生层取不到。
  用 `gg_read_many` 同时盯住多个候选地址是更适合 agent 的替代。

---

## 自检

服务启动后，可以用仓库里的冒烟测试确认协议层是否正常：

```bash
python3 tools/mcp_smoke_test.py http://127.0.0.1:8788/mcp <令牌>
```

只做只读探测，会检查鉴权、握手、工具列表、错误处理和只读拦截。

扫描器本身的协议测试（不需要 Android，直接在 Linux 上跑）：

```bash
g++ -std=c++11 -O2 -D_GNU_SOURCE -D_LARGEFILE64_SOURCE \
  android/app/src/main/cpp/scanner_root_optimized.cpp -o /tmp/scanner_root
python3 tools/scanner_test/test_scanner.py /tmp/scanner_root
```

会起一个靶子进程，验证六种类型的搜索、二次过滤、模糊比对、读写，
以及所有错误路径都返回错误而不是挂死。
