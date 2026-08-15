/// MCP 服务配置页
///
/// 把内存操作能力开放给 Claude Code / Codex 等 MCP 客户端。
/// 这个端点能以 root 读写任意进程内存，所以默认只绑回环、强制 token、只读模式开启。

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const _kPrimary = Color(0xFF8D6E63);
const _kAccent = Color(0xFF4E342E);
const _kSurface = Color(0xFFFFF9F0);
const _kTextPrimary = Color(0xFF3E2723);
const _kDanger = Color(0xFFC62828);

class McpPage extends StatefulWidget {
  const McpPage({super.key});

  @override
  State<McpPage> createState() => _McpPageState();
}

class _McpPageState extends State<McpPage> {
  static const _channel = MethodChannel('com.yl.aigg/bridge');

  bool _loading = true;
  bool _busy = false;
  bool _tokenVisible = false;

  bool _running = false;
  int _port = 8788;
  String _token = '';
  bool _readOnly = true;
  bool _lanEnabled = false;
  bool _autoStart = false;
  String _localEndpoint = '';
  String? _lanEndpoint;
  String? _lastError;

  List<dynamic> _callLog = const [];

  late final TextEditingController _portCtrl = TextEditingController();

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  @override
  void dispose() {
    _portCtrl.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    try {
      final s = await _channel.invokeMethod('mcpStatus');
      final log = await _channel.invokeMethod('mcpCallLog', {'limit': 30});
      if (!mounted) return;
      setState(() {
        _running = s['running'] == true;
        _port = s['port'] ?? 8788;
        _token = s['token'] ?? '';
        _readOnly = s['readOnly'] ?? true;
        _lanEnabled = s['lanEnabled'] ?? false;
        _autoStart = s['autoStart'] ?? false;
        _localEndpoint = s['localEndpoint'] ?? '';
        _lanEndpoint = s['lanEndpoint'];
        _lastError = s['lastError'];
        _callLog = log ?? const [];
        _portCtrl.text = '$_port';
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _lastError = '读取状态失败: $e';
      });
    }
  }

  Future<void> _toggleServer() async {
    setState(() => _busy = true);
    try {
      await _channel.invokeMethod(_running ? 'mcpStop' : 'mcpStart');
      // 服务启停是异步的，给前台服务一点时间落地
      await Future.delayed(const Duration(milliseconds: 700));
      await _refresh();
    } catch (e) {
      _toast('操作失败: $e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _setConfig({
    int? port,
    bool? lanEnabled,
    bool? readOnly,
    bool? autoStart,
    bool restart = false,
  }) async {
    try {
      await _channel.invokeMethod('mcpSetConfig', {
        if (port != null) 'port': port,
        if (lanEnabled != null) 'lanEnabled': lanEnabled,
        if (readOnly != null) 'readOnly': readOnly,
        if (autoStart != null) 'autoStart': autoStart,
        'restart': restart,
      });
      if (restart) await Future.delayed(const Duration(milliseconds: 700));
      await _refresh();
    } catch (e) {
      _toast('保存失败: $e');
    }
  }

  void _copy(String text, String label) {
    Clipboard.setData(ClipboardData(text: text));
    _toast('$label 已复制');
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(msg), duration: const Duration(seconds: 2)),
    );
  }

  String get _connectCommand {
    final ep = _lanEndpoint ?? _localEndpoint;
    return 'claude mcp add --transport http gg-modifier $ep '
        '--header "Authorization: Bearer $_token"';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFFDFBF7),
      appBar: AppBar(
        title: const Text('MCP 服务'),
        backgroundColor: _kSurface,
        foregroundColor: _kTextPrimary,
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _loading ? null : _refresh,
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                _intro(),
                const SizedBox(height: 16),
                _statusCard(),
                const SizedBox(height: 16),
                _connectCard(),
                const SizedBox(height: 16),
                _safetyCard(),
                const SizedBox(height: 16),
                _settingsCard(),
                const SizedBox(height: 16),
                _logCard(),
                const SizedBox(height: 32),
              ],
            ),
    );
  }

  Widget _sectionTitle(String t) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Text(t,
            style: const TextStyle(
                fontSize: 16, fontWeight: FontWeight.bold, color: _kTextPrimary)),
      );

  Widget _intro() {
    return Card(
      color: _kSurface,
      child: const Padding(
        padding: EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('把内存操作交给外部 AI',
                style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
            SizedBox(height: 8),
            Text(
              '开启后，Claude Code、Codex 等 MCP 客户端可以直接调用本机的进程列表、'
              '内存搜索、读写与冻结能力，由它们负责推理和收敛，App 只负责以 root 执行。\n\n'
              '内存搜索本来就是「搜索 → 让数值变化 → 再过滤」的多轮迭代，'
              '交给成熟 agent 比内置对话更合适。',
              style: TextStyle(fontSize: 13, height: 1.6, color: Color(0xFF5D4037)),
            ),
          ],
        ),
      ),
    );
  }

  Widget _statusCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.circle,
                    size: 12, color: _running ? Colors.green : Colors.grey),
                const SizedBox(width: 8),
                Text(
                  _running ? '服务运行中' : '服务未启动',
                  style: const TextStyle(
                      fontSize: 16, fontWeight: FontWeight.bold, color: _kTextPrimary),
                ),
                const Spacer(),
                if (_running && !_readOnly)
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                    decoration: BoxDecoration(
                      color: const Color(0xFFFBE9E7),
                      borderRadius: BorderRadius.circular(4),
                    ),
                    child: const Text('可写',
                        style: TextStyle(fontSize: 11, color: _kDanger)),
                  ),
              ],
            ),
            if (_lastError != null) ...[
              const SizedBox(height: 10),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: const Color(0xFFFDECEA),
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Text(_lastError!,
                    style: const TextStyle(fontSize: 12, color: _kDanger)),
              ),
            ],
            const SizedBox(height: 14),
            SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                onPressed: _busy ? null : _toggleServer,
                icon: Icon(_running ? Icons.stop : Icons.play_arrow),
                label: Text(_busy
                    ? '处理中…'
                    : _running
                        ? '停止服务'
                        : '启动服务'),
                style: FilledButton.styleFrom(
                  backgroundColor: _running ? _kDanger : _kPrimary,
                  padding: const EdgeInsets.symmetric(vertical: 14),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _copyRow(String label, String value, {bool mono = true, bool secret = false}) {
    final masked = value.length > 6 ? '${value.substring(0, 6)}${'•' * 20}' : '•' * 20;
    final display = secret && !_tokenVisible ? masked : value;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(label,
                  style: const TextStyle(
                      fontSize: 12, color: Color(0xFF8D6E63), fontWeight: FontWeight.w600)),
              const Spacer(),
              if (secret)
                InkWell(
                  onTap: () => setState(() => _tokenVisible = !_tokenVisible),
                  child: Icon(
                      _tokenVisible ? Icons.visibility_off : Icons.visibility,
                      size: 18,
                      color: _kPrimary),
                ),
              const SizedBox(width: 12),
              InkWell(
                onTap: () => _copy(value, label),
                child: const Icon(Icons.copy, size: 18, color: _kPrimary),
              ),
            ],
          ),
          const SizedBox(height: 4),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: _kSurface,
              borderRadius: BorderRadius.circular(6),
            ),
            child: SelectableText(
              display,
              style: TextStyle(
                fontSize: 12,
                fontFamily: mono ? 'monospace' : null,
                color: _kTextPrimary,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _connectCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _sectionTitle('连接方式'),
            _copyRow('本机地址', _localEndpoint),
            if (_lanEndpoint != null) _copyRow('局域网地址', _lanEndpoint!),
            _copyRow('访问令牌', _token, secret: true),
            const SizedBox(height: 8),
            const Text('在 Claude Code 里执行：',
                style: TextStyle(fontSize: 12, color: Color(0xFF5D4037))),
            const SizedBox(height: 6),
            InkWell(
              onTap: () => _copy(_connectCommand, '连接命令'),
              child: Container(
                width: double.infinity,
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: const Color(0xFF3E2723),
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Text(
                        _connectCommand,
                        style: const TextStyle(
                            fontSize: 11,
                            fontFamily: 'monospace',
                            color: Color(0xFFFFF3E0),
                            height: 1.5),
                      ),
                    ),
                    const SizedBox(width: 8),
                    const Icon(Icons.copy, size: 16, color: Color(0xFFA1887F)),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 10),
            const Text(
              '若 AI 客户端就跑在这台手机上，用本机地址即可；'
              '在电脑或另一台手机上，需先开启下方的「允许局域网访问」并使用局域网地址。\n'
              '大范围内存扫描可能持续数十秒，客户端侧建议把 MCP 超时调大。',
              style: TextStyle(fontSize: 11, color: Color(0xFF8D6E63), height: 1.5),
            ),
          ],
        ),
      ),
    );
  }

  Widget _safetyCard() {
    return Card(
      color: _readOnly ? _kSurface : const Color(0xFFFDECEA),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _sectionTitle('安全'),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              value: _readOnly,
              title: const Text('只读模式', style: TextStyle(fontSize: 14)),
              subtitle: Text(
                _readOnly
                    ? '写内存、冻结、执行脚本会被拒绝。搜索与读取不受影响。'
                    : '⚠️ AI 可以直接写入游戏进程内存。判断失误不易回溯，用完建议重新开启。',
                style: TextStyle(
                    fontSize: 12, color: _readOnly ? const Color(0xFF8D6E63) : _kDanger),
              ),
              onChanged: (v) async {
                if (!v) {
                  final ok = await _confirm(
                    '关闭只读模式',
                    '关闭后，连接进来的 AI 可以直接修改游戏进程内存、设置冻结、执行 Lua 脚本。\n\n'
                    '请确认你信任当前连接的客户端。',
                  );
                  if (!ok) return;
                }
                // readOnly 由每次工具调用实时读取，不需要重启服务。
                // 早期版本这里传了 restart:true，反而会把正在运行的服务弄挂。
                await _setConfig(readOnly: v);
              },
            ),
            const Divider(height: 1),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              value: _lanEnabled,
              title: const Text('允许局域网访问', style: TextStyle(fontSize: 14)),
              subtitle: Text(
                _lanEnabled
                    ? '⚠️ 同一局域网内的设备都能尝试连接，仅靠令牌保护。'
                    : '仅监听 127.0.0.1，只有本机能连。',
                style: TextStyle(
                    fontSize: 12, color: _lanEnabled ? _kDanger : const Color(0xFF8D6E63)),
              ),
              onChanged: (v) async {
                if (v) {
                  final ok = await _confirm(
                    '开启局域网访问',
                    '服务将监听所有网卡，同一局域网内的任何设备都能尝试连接，'
                    '安全性仅由访问令牌保证。\n\n'
                    '在公共 Wi-Fi 下不建议开启。',
                  );
                  if (!ok) return;
                }
                await _setConfig(lanEnabled: v, restart: true);
              },
            ),
            const Divider(height: 1),
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('重新生成访问令牌', style: TextStyle(fontSize: 14)),
              subtitle: const Text('旧令牌立即失效，已连接的客户端需重新配置',
                  style: TextStyle(fontSize: 12, color: Color(0xFF8D6E63))),
              trailing: const Icon(Icons.refresh, color: _kPrimary),
              onTap: () async {
                final ok = await _confirm('重新生成令牌', '当前令牌将立即作废，需要在客户端重新配置。');
                if (!ok) return;
                await _channel.invokeMethod('mcpRegenerateToken');
                await _refresh();
                _toast('已重新生成');
              },
            ),
          ],
        ),
      ),
    );
  }

  Widget _settingsCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _sectionTitle('服务设置'),
            Row(
              children: [
                const Expanded(
                  flex: 2,
                  child: Text('监听端口', style: TextStyle(fontSize: 14)),
                ),
                Expanded(
                  flex: 3,
                  child: TextField(
                    controller: _portCtrl,
                    keyboardType: TextInputType.number,
                    style: const TextStyle(fontSize: 14),
                    decoration: const InputDecoration(
                      isDense: true,
                      contentPadding: EdgeInsets.symmetric(horizontal: 10, vertical: 10),
                      border: OutlineInputBorder(),
                      hintText: '1024-65535',
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                TextButton(
                  onPressed: () async {
                    final p = int.tryParse(_portCtrl.text.trim());
                    if (p == null || p < 1024 || p > 65535) {
                      _toast('端口需在 1024-65535 之间');
                      return;
                    }
                    await _setConfig(port: p, restart: _running);
                    _toast('端口已保存');
                  },
                  child: const Text('保存'),
                ),
              ],
            ),
            const Divider(height: 20),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              value: _autoStart,
              title: const Text('随 App 启动', style: TextStyle(fontSize: 14)),
              subtitle: const Text('打开 App 时自动开启 MCP 服务',
                  style: TextStyle(fontSize: 12, color: Color(0xFF8D6E63))),
              onChanged: (v) => _setConfig(autoStart: v),
            ),
          ],
        ),
      ),
    );
  }

  Widget _logCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                _sectionTitle('调用记录'),
                const Spacer(),
                Text('${_callLog.length} 条',
                    style: const TextStyle(fontSize: 12, color: Color(0xFF8D6E63))),
              ],
            ),
            if (_callLog.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 12),
                child: Text('还没有调用。连接客户端后，AI 每次调用工具都会记录在这里。',
                    style: TextStyle(fontSize: 12, color: Color(0xFF8D6E63))),
              )
            else
              ..._callLog.map((e) {
                final ok = e['ok'] == true;
                final ts = DateTime.fromMillisecondsSinceEpoch(
                    (e['timestamp'] as num).toInt());
                final t = '${ts.hour.toString().padLeft(2, '0')}:'
                    '${ts.minute.toString().padLeft(2, '0')}:'
                    '${ts.second.toString().padLeft(2, '0')}';
                return Padding(
                  padding: const EdgeInsets.symmetric(vertical: 3),
                  child: Row(
                    children: [
                      Icon(ok ? Icons.check_circle : Icons.error,
                          size: 14, color: ok ? Colors.green : _kDanger),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text('${e['tool']}',
                            style: const TextStyle(
                                fontSize: 12, fontFamily: 'monospace')),
                      ),
                      Text('${e['costMs']}ms',
                          style: const TextStyle(
                              fontSize: 11, color: Color(0xFFA1887F))),
                      const SizedBox(width: 10),
                      Text(t,
                          style: const TextStyle(
                              fontSize: 11, color: Color(0xFFA1887F))),
                    ],
                  ),
                );
              }),
          ],
        ),
      ),
    );
  }

  Future<bool> _confirm(String title, String message) async {
    final r = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: Text(message, style: const TextStyle(fontSize: 13, height: 1.5)),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: FilledButton.styleFrom(backgroundColor: _kAccent),
            child: const Text('确认'),
          ),
        ],
      ),
    );
    return r == true;
  }
}
