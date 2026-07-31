# 在任意 RustDesk 官方版本上实现 Windows/Android mTLS 定制

## 0. 教程适用范围与目标

这是一份可独立使用的实现教程，面向需要在任意 RustDesk 官方正式 Release 源码上完成相同定制的 AI。开始时只假设具备：

- 一个从目标 RustDesk 官方正式 Release 创建的干净仓库；
- 对该仓库和 GitHub Actions 的写入权限；
- Windows/Android 客户端证书及配套 nginx mTLS 服务入口；
- 本文记录的需求规格和经过 1.4.9 真机验证的参考实现。

本文不依赖任何特定 fork、所有者、分支名或提交历史。实现时：

- 先完整阅读本文件，再检查新版本的 `AGENTS.md`、目录结构、Cargo 清单、Flutter/Android 工程和官方构建工作流。
- 新版代码结构、依赖版本和构建命令可能变化。必须按本文的安全边界和行为重新适配，不能机械套用旧行号或旧依赖版本。
- 只做实现该功能所需的最小改动。尤其要避免格式化整个大型 Rust/Dart 文件、改变换行符或顺手重构上游代码。

最终交付目标：

1. Windows 使用系统证书库中的客户端证书，通过 SChannel 与前置 nginx 建立 mTLS。
2. Android 让用户通过系统 KeyChain 选择客户端证书，通过 Android `SSLSocket` 建立 mTLS。
3. mTLS 只接管客户端到 RustDesk ID/HBBS 和 relay/HBBR 的 TCP 服务连接，不全局替换普通 TCP。
4. Windows Actions 只构建并上传 `librustdesk.dll`。
5. Android Actions 构建并上传 ARM64 APK。
6. 只保留两个独立、仅手动触发的工作流：
   - `.github/workflows/windows-dll.yml`
   - `.github/workflows/android-apk.yml`
7. 推送代码不会自动构建；由用户在 Actions 页面手动触发。
8. 产物只通过 Actions artifact 下载，不创建 GitHub Release。
9. 确保两个工作流文件存在于 GitHub 默认分支，否则新文件名的 `workflow_dispatch` 不会在 Actions 页面注册。
10. 持续修复构建问题，直到两个 Actions 都成功并存在可下载 artifact；随后由用户做真实功能测试。

### 0.1 强制阶段门禁：禁止提前构建或提前宣布完成

以下规则是本教程的强制执行条件，优先级高于“尽快得到一次成功构建”。

#### 阶段 A：实现与静态审计

必须先完成全部事项：

- Windows 和 Android 的业务代码都已实现，不存在先构建一个平台、稍后再补另一个平台的半成品状态；
- HBBS/HBBR TCP 调用点已经逐个迁移并完成反向审计；
- Windows hbb_common 实现/补丁完整；
- Android Flutter、Kotlin、JNI、Rust、R8 改动完整；
- `Cargo.lock` 已与最终 manifest 对齐；
- 四个 Flutter bridge 文件已基于目标版本重新生成；
- 两个最终 workflow 已写完，临时 bridge/debug workflow 已删除；
- `.github/workflows/` 中没有多余产品 workflow；
- 两个 workflow 已与目标版本的官方工作流、本文记录的已验证工作流逐项对照；不能凭印象重新猜测系统包、NDK 安装方式、vcpkg triplet 或签名配置；
- workflow 中的平台环境契约已显式写出并快速失败：Windows 的 vcpkg target/host triplet 一致，Android 的三个 NDK 路径一致；
- 补丁中声明的新增模块确实包含在补丁内；不能只增加 `mod` 声明却漏掉新文件；
- 没有 TODO、占位代码、未处理的编译分支或计划稍后补写的必要文件；
- `git diff -w`、`git diff --check`、补丁检查和 `actionlint` 全部完成；
- 全部最终代码已提交并推送，得到唯一的 `<FINAL_COMMIT_SHA>`。

在以上条件全部满足前：

> **禁止触发 Windows 或 Android Actions 构建。**

构建不能替代代码完整性检查。除非用户明确要求一次早期 smoke build，否则不要用半成品提交触发 Actions；即使早期构建偶然成功，也不能把它当成最终验证。

#### 阶段 B：最终提交的 Actions 验证

只有阶段 A 通过后才能：

1. 使用 `<FINAL_COMMIT_SHA>` 所在默认分支手动触发两个 workflow；
2. 核对两个 run 的 `headSha` 都等于 `<FINAL_COMMIT_SHA>`；
3. 持续监控到两个 run 进入 terminal 状态，不能只负责 dispatch 后就停止；
4. 任一失败时读取完整日志、修复、提交、推送并重新运行；
5. 任何构建后的代码变化都会使旧 artifact 失效，必须用新的最终 commit 重新构建受影响平台；
6. 下载并检查 artifact 的文件名、架构和内容。

只有一个平台成功、另一个仍在运行/失败，或 run 对应较旧 commit 时，都不能说“构建完成”。

#### 阶段 C：真实功能验收

Actions 成功只证明最终源码能够产生 artifact，不证明 mTLS、远控数据流或证书行为正确。

- AI 可以报告：“两个最终 artifact 已生成，等待用户实机测试。”
- 在用户明确确认 Windows DLL 和 Android APK 都通过真实功能测试前，禁止使用“任务完成”“实现完成”“全部完成”等结论。
- 如果没有可用真机、证书或服务端，应报告“等待外部功能验证”，而不是宣布成功。
- 用户反馈问题后必须继续诊断和修复；修复后重新走阶段 A、B，并再次等待阶段 C。

---

## 1. 经过验证的参考行为（1.4.9）

以下参考实现已经在 RustDesk 1.4.9 的真实 Windows 和 Android ARM64 设备上完成验证：

| 平台 | 最终 Actions 产物 | 真实测试结果 |
| --- | --- | --- |
| Windows x64 | 仅 `librustdesk.dll` | 与官方同版本客户端配合替换后，注册、连接、画面、鼠标键盘和传输正常 |
| Android ARM64 | APK | 安装、选择 KeyChain 证书、连接、远控和重连正常 |

Windows DLL-only 的成立条件：

- DLL 必须由与官方客户端完全相同的 RustDesk Release 源码和架构构建。
- Rust feature 必须与官方 Flutter Windows 客户端需要的导出 ABI 一致。1.4.9 使用：

```powershell
cargo build --locked --release --lib --features "flutter,hwcodec,vram"
```

- 不要把某个版本的 DLL 放进另一个版本，也不要在 x86/x64/ARM64 之间混用。
- 替换 DLL 前停止 RustDesk UI 和服务，备份官方 DLL，再替换并重启。

参考 Android Actions 使用调试签名构建 release APK。GitHub 托管 runner 生成的调试签名可能随构建变化，因此安装新构建时可能需要先卸载旧 APK；卸载也会清除已保存的 KeyChain alias，必须重新设置服务器并重新选择证书。

下列 1.4.9 构建环境仅供理解已验证组合；每个目标版本都必须重新从该版本的官方源码/工作流确定：

| 项目 | 1.4.9 已验证值 |
| --- | --- |
| Windows runner | `windows-2022` |
| Android runner | `ubuntu-24.04` |
| Rust | `1.75` |
| LLVM | `15.0.6` |
| Flutter | `3.24.5` |
| JDK | 17 |
| Android NDK | `r28c` |
| cargo-ndk | `3.1.2` |
| flutter_rust_bridge codegen | `1.80.1` |
| cargo-expand | `1.0.95` |
| vcpkg commit | `120deac3062162151622ca4860575a33844ba10b` |
| Windows vcpkg triplet | `x64-windows-static` |
| Actions cache | 最终全部取消，按冷构建设计 |
| Artifact retention | 90 天 |

---

## 2. 网络架构与不可改变的安全边界

网络路径是：

```text
RustDesk client
  └─ TCP + TLS + client certificate
       └─ nginx stream (验证客户端证书、终止外层 TLS)
            └─ 明文 RustDesk TCP protocol
                 ├─ HBBS / ID service
                 └─ HBBR / relay service
```

RustDesk 自己的协议加密和 `secure_tcp()` 仍然保留。这里增加的是外层 mTLS，不能删除 RustDesk 原有协议安全层。

必须保持：

- 私钥只存在于 Windows Current User 证书库或 Android KeyChain。
- 不支持从 PEM/PFX 路径读取客户端私钥。
- 不把 P12、私钥、密码、alias、证书 DER 或敏感配置写进日志、仓库或 Actions artifact。
- 使用系统默认服务端信任链。
- 开启 SNI 和 DNS 主机名验证。
- 不得加入 `accept_invalid_certificates`、`accept_invalid_hostnames`、空 TrustManager 或其他跳过验证逻辑。
- 服务端入口必须使用 DNS 名称，例如 `rr.example.com:62171`，不能用 IP 地址绕过名称验证。
- 如果 nginx 服务端证书使用私有 CA，应把 CA 正确安装到 Windows/Android 系统信任库，而不是在代码里关闭验证。
- 客户端证书缺失、数量不符、无私钥、过期、OU/EKU 不符、KeyChain alias 失效、TLS 握手失败时必须安全失败。
- 失败时不得回退为普通明文 TCP。

本实现只替换 RustDesk 服务端 TCP 连接。下列路径保持上游行为：

- peer-to-peer 直连；
- 打洞后的直接 TCP；
- UDP；
- WebSocket/WebRTC；
- SOCKS/代理路径；
- 本地 IPC；
- `peer_online` 等不属于该 nginx mTLS 入口的辅助端口。

不要把 mTLS 写进通用 `connect_tcp()`，否则会破坏直连、局域网、代理和其他协议路径。

---

## 3. 客户端证书约定

### 3.1 两个平台共同规则

合格的叶子证书必须：

- 当前时间位于证书有效期内；
- Subject 中存在精确的 `OU=RustDeskClient`；
- EKU 不存在（表示用途未限制），或包含：
  - TLS clientAuth OID：`1.3.6.1.5.5.7.3.2`；
  - Windows 解析库中的 `any` EKU 也允许；
- 有可用的关联私钥。

### 3.2 Windows 行为

- 打开 Current User 的 `MY`/`My` 证书库。
- 自动筛选所有合格证书。
- 必须恰好一张：
  - 0 张：失败并提示没有合格证书；
  - 多于 1 张：失败并提示存在多张；
  - 1 张：使用它建立 SChannel credential。
- 由用户确保 Current User 证书库中只有一张符合条件的证书。
- 不要偷偷改查找范围到 Local Machine，也不要在多个候选中按 CN、过期时间或排序任选一张。

### 3.3 Android 行为

Android 普通应用不能可靠、合规地枚举全部 KeyChain 私钥 alias，因此不要尝试照搬 Windows 的自动枚举。

正确流程：

1. 用户把 P12 导入 Android 系统，凭据用途选择“VPN 和应用”。
2. 用户在 RustDesk 设置页点击 `mTLS client certificate`。
3. 前台 `Activity` 调用 `KeyChain.choosePrivateKeyAlias()`。
4. App 在后台线程读取选择结果对应的私钥句柄和证书链，并验证叶子证书。
5. 只把验证后的 alias 存进名为 `rustdesk-mtls` 的 `SharedPreferences`。
6. 每次建立服务连接时重新从 KeyChain 读取私钥句柄和证书链并重新验证。
7. alias 被删除、权限撤销、证书失效或用户取消时，清除保存的 alias 并安全失败。

alias 不是私钥，可以保存；但不要把 alias 返回 Dart、写入日志或上传。

---

## 4. nginx 参考配置

以下是 1.4.9 实际验证通过的拓扑。未来可改主机名、IP、证书路径和端口，但 TLS/mTLS 语义必须一致：

```nginx
stream {
    log_format stream_basic '$remote_addr:$remote_port -> $server_addr:$server_port '
                            'upstream=$upstream_addr status=$status '
                            'bytes_sent=$bytes_sent bytes_received=$bytes_received '
                            'session_time=$session_time';

    access_log /var/log/nginx/rustdesk-stream-access.log stream_basic;
    error_log /var/log/nginx/rustdesk-stream-error.log info;

    upstream rustdesk_hbbs {
        server 10.0.100.104:62171;
    }

    upstream rustdesk_hbbr {
        server 10.0.100.104:62172;
    }

    server {
        listen 62171 ssl;
        proxy_pass rustdesk_hbbs;

        ssl_certificate /etc/TLS/rustdesk/fullchain.cer;
        ssl_certificate_key /etc/TLS/rustdesk/key.key;
        ssl_client_certificate /etc/TLS/rustdesk/client-ca.crt;
        ssl_verify_client on;
        ssl_verify_depth 1;
        ssl_protocols TLSv1.2 TLSv1.3;

        proxy_connect_timeout 10s;
        proxy_timeout 3600s;
    }

    server {
        listen 62172 ssl;
        proxy_pass rustdesk_hbbr;

        ssl_certificate /etc/TLS/rustdesk/fullchain.cer;
        ssl_certificate_key /etc/TLS/rustdesk/key.key;
        ssl_client_certificate /etc/TLS/rustdesk/client-ca.crt;
        ssl_verify_client on;
        ssl_verify_depth 1;
        ssl_protocols TLSv1.2 TLSv1.3;

        proxy_connect_timeout 10s;
        proxy_timeout 3600s;
    }
}
```

客户端 RustDesk 配置应显式填写 DNS 入口及对应端口。

服务端验证时：

- nginx stream access log 应出现对应客户端会话、字节数和持续时间；
- 无证书/错误证书应在 nginx TLS 层失败，不应进入 HBBS/HBBR 配对；
- HBBR 日志出现 `Both are raw` 是正常的：外层 TLS 已在 nginx 终止，nginx 到 HBBR 是原始 RustDesk 流；
- 不能仅凭 HBBS 注册日志判断远控正常，还必须验证 HBBR 长时间双向数据流。

---

## 5. 新版本迁移总流程

### 5.1 固定并验证官方基线

1. 确认仓库确实来自目标正式 Release，而不是任意开发分支快照。
2. 记录：
   - RustDesk 版本；
   - 对应 tag/commit；
   - Rust MSRV/toolchain；
   - Flutter 版本；
   - Android NDK、JDK、Gradle/AGP；
   - vcpkg commit/triplet；
   - `flutter_rust_bridge` 版本及生成方法；
   - 官方 Windows 和 Android features/build 命令。
3. 检查子模块：

```bash
git submodule update --init --recursive
git submodule status --recursive
```

4. 如果 Release 源码是压缩包导入的，GitHub 的源码压缩包可能不包含子模块内容。检查：

```bash
git ls-files -s libs/hbb_common
```

- mode 为 `160000`：它仍是 gitlink，使用本文的父仓库补丁方案。
- 如果 `libs/hbb_common` 已作为普通目录完整纳入仓库：可以直接最小修改其中代码，不必人为再造子模块补丁。
- 如果目录缺失：必须按该 Release 记录的 gitlink commit 恢复子模块，不能随意使用 hbb_common 最新版。

5. 确认工作区干净，并先理解新版官方工作流。不要一开始就复制 1.4.9 的工具链版本。
6. 如本机 Git 访问需要代理，可一次性使用 `127.0.0.1:7890`，不要把代理写入仓库：

```bash
git -c http.proxy=http://127.0.0.1:7890 submodule update --init --recursive
```

### 5.2 搜索新版连接入口

不要依赖 1.4.9 行号。优先搜索：

```bash
rg -n "connect_tcp\\(" src libs/hbb_common
rg -n "RENDEZVOUS_PORT|RELAY_PORT|create_relay|start_tcp|check_id" src
rg -n "KeyChain|MethodChannel|invokeMethod" flutter
rg -n "APPLICATION_CONTEXT|JavaVM|JVM" libs/scrap src
```

把调用分成：

- 到 HBBS/ID 的服务 TCP；
- 到 HBBR/relay 的服务 TCP；
- peer 直连、打洞、本地、WebSocket、WebRTC、UDP、代理等非目标连接。

只替换前两类。

### 5.3 建立统一的窄入口

1.4.9 在 `src/common.rs` 增加：

```rust
/// Connect an ID or relay service endpoint.
///
/// Windows uses SChannel and Android uses its KeyChain-backed platform bridge.
/// Callers use this narrow helper rather than changing general TCP behavior.
pub async fn connect_rustdesk_service(target: String, timeout_ms: u64) -> ResultType<Stream> {
    #[cfg(target_os = "windows")]
    {
        socket_client::connect_tcp_mtls_service(target, timeout_ms).await
    }
    #[cfg(target_os = "android")]
    {
        crate::android_mtls::connect_service(target, timeout_ms).await
    }
    #[cfg(not(any(target_os = "windows", target_os = "android")))]
    {
        socket_client::connect_tcp(target, timeout_ms).await
    }
}
```

如果新版本的返回类型、timeout 类型或网络抽象已经变化，应保持同样的职责边界并适配新版类型，而不是强行恢复旧签名。

1.4.9 最终替换的服务调用点是：

- `src/common.rs`
  - 多 rendezvous server 连通性测试。
- `src/client.rs`
  - 初始 rendezvous TCP 连接；
  - 备用 rendezvous server；
  - 多次 rendezvous 尝试；
  - client 侧 relay 连接；
  - health-check TCP。
- `src/rendezvous_mediator.rs`
  - `RendezvousMediator::start_tcp`；
  - 向 HBBS 发 relay response 的 TCP；
  - intranet/local-address 获取用的 HBBS TCP；
  - TCP hole punch 前为取得本地地址而建立的 HBBS TCP。
- `src/server.rs`
  - host/server 侧 `create_relay_connection_`。
- `src/ui_interface.rs`
  - `check_id`。

1.4.9 明确保留普通 TCP 的例子：

- `src/client.rs` 中 `peer_online` 的辅助端口连接仍直接调用上游 `hbb_common::socket_client::connect_tcp()`。
- 打洞后到 peer 的 `connect_tcp_local()` 仍是普通 TCP。

新版可能增加、删除或重命名服务调用点。迁移完成后重新搜索全部 `connect_tcp`，逐个解释为什么属于或不属于 mTLS 服务入口。

不要删除调用点后仍然存在的 RustDesk `secure_tcp()`。

---

## 6. Windows 实现规格

### 6.1 代码放置策略

1.4.9 的 `libs/hbb_common` 是子模块。最终实现没有提交脏子模块，而是在父仓库保存：

```text
.github/patches/hbb_common_windows_mtls.diff
```

该补丁修改：

```text
libs/hbb_common/Cargo.toml
libs/hbb_common/src/lib.rs
libs/hbb_common/src/socket_client.rs
libs/hbb_common/src/mtls_windows.rs   # 新文件
```

父仓库工作流在构建前执行：

```powershell
git -C libs/hbb_common apply --check ../../.github/patches/hbb_common_windows_mtls.diff
git -C libs/hbb_common apply ../../.github/patches/hbb_common_windows_mtls.diff
```

未来如果 hbb_common 不再是子模块，应直接修改新版源码，删除不必要的补丁机制。

### 6.2 1.4.9 依赖参考

1.4.9 在 hbb_common 的 Windows target dependencies 中使用：

```toml
[target.'cfg(target_os = "windows")'.dependencies]
schannel = "=0.1.23"
mio = { version = "=0.8.11", features = ["net", "os-poll"] }
x509-parser = "=0.16.0"
```

这只是兼容 1.4.9/MSRV 的已验证组合。新版本应：

- 优先复用新版已经存在的依赖版本；
- 检查新版 MSRV；
- 查看 `schannel`/`mio` API 是否变化；
- 避免为了这个功能执行无关的全量 `cargo update`；
- 让最终 `Cargo.lock` 与实际修改后的 hbb_common manifest 一致。

### 6.3 hbb_common 导出

在 `hbb_common/src/lib.rs`：

```rust
#[cfg(target_os = "windows")]
pub mod mtls_windows;
```

在 `hbb_common/src/socket_client.rs` 增加专用入口：

```rust
#[cfg(target_os = "windows")]
pub async fn connect_tcp_mtls_service(
    target: String,
    ms_timeout: u64,
) -> ResultType<Stream> {
    Ok(Stream::Tcp(
        crate::mtls_windows::connect_service(target, ms_timeout).await?,
    ))
}
```

不要修改通用 `connect_tcp()` 的默认行为。

### 6.4 `mtls_windows.rs` 必须实现的行为

模块职责：

1. 检查 SOCKS：

```rust
if Config::get_socks().is_some() {
    anyhow::bail!("Windows mTLS service connections do not support SOCKS proxies");
}
```

2. 从目标 `host:port` 解析 DNS hostname：
   - 空 host 失败；
   - IP literal 失败；
   - 无效 IPv6/bracket 形式失败；
   - hostname 用于 SChannel domain/SNI/hostname verification。
3. 把 DNS 解析、TCP connect、证书访问和 TLS handshake 放在 `tokio::task::spawn_blocking`，不能阻塞 Tokio worker，也不能创建嵌套 runtime。
4. TCP 使用：
   - `TcpStream::connect_timeout`；
   - `TCP_NODELAY`；
   - 保存真实 `local_addr`；
   - 转为 nonblocking `mio::net::TcpStream`。
5. 从 Current User `My` store 选择唯一证书：
   - `CertStore::open_current_user("My")`；
   - `certificate.is_time_valid()`；
   - 用 `x509-parser` 从 DER 解析 OU/EKU；
   - OU 精确等于 `RustDeskClient`；
   - EKU absent/clientAuth/any；
   - `certificate.private_key().compare_key(true).silent(true).acquire().is_ok()`；
   - 0/多张时返回不同的清晰错误。
6. 创建 credential 时必须把证书上下文传给 builder：

```rust
let mut credentials = SchannelCred::builder();
credentials.cert(certificate);
let credentials = credentials.acquire(Direction::Outbound)?;
```

只创建空 `SchannelCred` 会导致客户端证书没有真正参与握手，这是历史故障。

7. TLS builder：

```rust
let mut builder = tls_stream::Builder::new();
builder.domain(host);
```

使用默认服务端链和主机名验证，不添加任何 `accept_invalid_*`。

### 6.5 SChannel 异步适配器

SChannel crate 暴露的是阻塞式 `Read + Write`。1.4.9 最终使用一个专用 OS 线程和 `mio`，把它包装成 Tokio 可用的 `AsyncRead + AsyncWrite`。

核心结构：

```rust
enum WorkerCommand {
    Write(Vec<u8>),
    Shutdown,
}

struct ChannelStream {
    commands: std::sync::mpsc::Sender<WorkerCommand>,
    wake: Arc<mio::Waker>,
    incoming: Mutex<tokio::sync::mpsc::UnboundedReceiver<io::Result<Vec<u8>>>>,
    pending: Vec<u8>,
    closed: bool,
}
```

必须保持的状态机：

- handshake 被 `HandshakeError::Interrupted` 中断时，同时注册：

```rust
Interest::READABLE.add(Interest::WRITABLE)
```

- handshake 完成后先注册 READABLE。
- worker 收到待发送数据后把 interest 切到 READABLE + WRITABLE。
- 发送队列清空后切回 READABLE。
- 用 `mio::Waker` 的 command token 唤醒 poll；不能忙等。
- readable event 中循环读取直到 `WouldBlock`。
- writable event 中处理：
  - 完整写入；
  - 部分写入，把余下内容放回队首；
  - `WouldBlock`；
  - 真正 I/O 错误。
- 读到 0、receiver 被关闭或 Shutdown 时干净退出。
- `AsyncRead::poll_read` 必须保存超出 `ReadBuf` 容量的 pending bytes。
- `AsyncWrite::poll_write` 把数据交给 worker 并 wake。
- `poll_shutdown` 通知 worker 并关闭底层 socket。
- 不持有锁跨 `.await`。
- 不在 Tokio runtime 中调用阻塞 TLS read/write。

这是 Windows 最关键的历史修复。早期实现能握手和连接，但只正确等待 readable，导致实际远控表现为：

- 画面延迟极高；
- 鼠标键盘事件严重延迟；
- 看起来像高丢包；
- 实际网络正常。

正确处理 handshake/read/write readiness、动态 writable interest、部分写和 worker wake 后问题消失。不要把这个问题错误归因于 nginx buffering 或真实网络质量。

---

## 7. Android 实现规格

### 7.1 最终真实语义改动文件

1.4.9 Android 最终需要：

```text
flutter/lib/mobile/pages/settings_page.dart
flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/MainActivity.kt
flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/MtlsCredentialManager.kt
flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/AndroidMtlsSocketBridge.kt
flutter/android/app/proguard-rules
flutter/android/gradle.properties
libs/scrap/src/android/ffi.rs
src/android_mtls.rs
src/lib.rs
```

1.4.9 最终并没有语义修改：

```text
flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/MainService.kt
flutter/android/app/src/main/kotlin/ffi.kt
src/flutter_ffi.rs
src/platform/windows.rs
根 Cargo.toml
```

如果 diff 显示这些大型文件有成千上万行变化，先用 `git diff -w` 检查，通常是 CRLF/LF 噪音。不要把噪音带到新版本。

### 7.2 Flutter 设置入口

在 Android、设置可修改且网络设置可见时增加一个 tile：

```dart
SettingsTile(
  title: const Text('mTLS client certificate'),
  description: const Text(
      'Choose a RustDeskClient certificate from Android system credentials'),
  leading: const Icon(Icons.verified_user),
  onPressed: (context) async {
    try {
      await gFFI.invokeMethod('select_mtls_client_certificate');
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
            content: Text('mTLS client certificate selected')));
      }
    } catch (_) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
            content: Text('No valid mTLS client certificate was selected')));
      }
    }
  },
)
```

新版如已有本地化体系，应该把新增文案纳入新版正常本地化，而不是机械保留硬编码；但不要因此大范围修改所有语言文件。

### 7.3 `MainActivity` 前台选择

在 `onCreate` 初始化：

```kotlin
private lateinit var mtlsCredentials: MtlsCredentialManager

mtlsCredentials = MtlsCredentialManager(applicationContext)
```

在现有 Flutter MethodChannel handler 中增加：

```kotlin
"select_mtls_client_certificate" -> {
    mtlsCredentials.choose(this) { choice ->
        runOnUiThread {
            choice.onSuccess { result.success(true) }
            choice.onFailure {
                result.error("mtls-credential", it.message, null)
            }
        }
    }
}
```

KeyChain chooser 只能从前台 Activity 调用。不要从 service、native reconnect 线程或后台 context 弹 chooser。

不要把 alias 返回 Dart。

### 7.4 `MtlsCredentialManager.kt`

类名和 package 在 1.4.9 是：

```kotlin
package com.carriez.flutter_hbb

class MtlsCredentialManager(private val context: Context)
```

需要：

- `PREFS = "rustdesk-mtls"`；
- `ALIAS = "client-keychain-alias"`；
- `CLIENT_AUTH_EKU = "1.3.6.1.5.5.7.3.2"`；
- `REQUIRED_OU = "RustDeskClient"`；
- 单线程 executor 执行 KeyChain 读取；
- `KeyChain.getPrivateKey()`；
- `KeyChain.getCertificateChain()`；
- 验证 chain 非空、叶子有效期、OU 和 EKU；
- 验证成功后才保存 alias；
- 任何失败都清除 alias；
- 每次新连接调用 `selectedCredential(context)` 重新加载和验证。

Subject OU 解析不能简单 `split(",")`。RFC 2253 值可能包含转义逗号，也可能用 `+` 连接多个 attribute。1.4.9 使用一个按转义状态扫描的分隔器，只在未转义的 `,` 或 `+` 处分割，再检查 attribute 名是否为 OU、值是否精确等于 `RustDeskClient`。

证书 `extendedKeyUsage == null` 表示未限制用途；非空时必须包含 clientAuth OID。

日志只能记录通用失败消息，不能记录 alias 或证书内容。

### 7.5 `AndroidMtlsSocketBridge.kt`

1.4.9 使用：

```kotlin
object AndroidMtlsSocketBridge
```

并提供 JNI 可调用入口：

```kotlin
@JvmStatic
fun open(
    context: Context,
    host: String,
    port: Int,
    timeoutMillis: Int,
): Int
```

行为顺序：

1. 要求 API >= 29。
2. 检查 hostname 非空、port 为 `1..65535`。
3. 调用 `MtlsCredentialManager.selectedCredential(context)`。
4. 创建 `SSLContext.getInstance("TLS")`。
5. 用固定 alias 的 `X509ExtendedKeyManager` 初始化：
   - client alias 始终返回已选 alias；
   - private key/chain 只在 requested alias 匹配时返回；
   - server alias 方法全部返回 null。
6. TrustManager 参数传 `null`，表示使用 Android 默认系统信任，不是关闭验证。
7. 创建 `SSLSocket`，设置连接/握手 timeout。
8. 在握手前设置：

```kotlin
sslParameters = sslParameters.apply {
    endpointIdentificationAlgorithm = "HTTPS"
}
```

9. `startHandshake()`。
10. 握手完成后必须：

```kotlin
soTimeout = 0
```

连接 timeout 只应用于 connect/handshake。若保留到长连接，会在正常空闲时错误断开 relay。

11. 使用：

```kotlin
val pair = ParcelFileDescriptor.createSocketPair()
```

12. 返回给 Rust：

```kotlin
return pair[0].detachFd()
```

13. Kotlin worker 使用 `pair[1]` 在两个方向泵送：
   - local Rust -> `SSLSocket.outputStream`；
   - `SSLSocket.inputStream` -> local Rust；
   - 使用独立线程以支持全双工；
   - 两个方向应持有独立、所有权明确的 fd；可用 `ParcelFileDescriptor.AutoCloseInputStream(pair[1].dup())` 与 `AutoCloseOutputStream(pair[1])`；
   - 不要写 `FileInputStream(pair[1].dup().fileDescriptor)` 后丢弃临时 `ParcelFileDescriptor`，否则它的回收/关闭可能提前使仍在泵送的 fd 失效；
   - 正确 flush/close；
   - 日志可记录方向和总字节数，但不能记录应用数据或凭据。

日志 tag：

```text
RustDeskMtls
```

关键日志：

```text
Opening mTLS connection to host:port
mTLS handshake completed
mTLS local-to-network stream ended after ... bytes
mTLS network-to-local stream ended after ... bytes
```

### 7.6 JNI class loader 与 application context

Rust 发起连接的线程是 native-created thread。该线程直接使用 JNI `FindClass` 往往只能访问 bootstrap class loader，会出现 `ClassNotFoundException`。

在 `libs/scrap/src/android/ffi.rs` 暴露已有 JVM 和 application context：

```rust
pub fn with_java_vm_and_application_context<T>(
    callback: impl FnOnce(&JavaVM, &GlobalRef) -> T,
) -> Option<T> {
    let jvm = JVM.read().ok()?;
    let context = APPLICATION_CONTEXT.read().ok()?;
    match (jvm.as_ref(), context.as_ref()) {
        (Some(jvm), Some(context)) => Some(callback(jvm, context)),
        _ => None,
    }
}
```

不要再维护第二份全局 Android context。

在 Rust JNI 调用中：

1. `vm.attach_current_thread()`；
2. 对 application context 调用 `getClassLoader()`；
3. 对 class loader 调用：

```text
loadClass("com.carriez.flutter_hbb.AndroidMtlsSocketBridge")
```

4. 再调用静态：

```text
open(Context, String, int, int) -> int
```

1.4.9 JNI descriptor：

```text
(Landroid/content/Context;Ljava/lang/String;II)I
```

新版如果 application ID/package 改变，必须同步更新 Kotlin package、class loader 字符串和 ProGuard rule。

### 7.7 `src/android_mtls.rs`

在 `src/lib.rs` 注册：

```rust
#[cfg(target_os = "android")]
mod android_mtls;
```

`connect_service(target, timeout_ms)` 必须：

1. 解析 DNS `host:port`；
2. 拒绝空 host、IP literal、带冒号的非预期 host 和 port 0；
3. 用 `tokio::task::spawn_blocking` 执行 JNI/KeyChain/connect/handshake；
4. 检查返回 fd >= 0；
5. 用 `FromRawFd` 接管 fd 所有权；
6. 设置 nonblocking；
7. 转为 `tokio::net::UnixStream`；
8. 包装进新版 RustDesk 的 framed stream。

最关键的类型：

```rust
// 1.4.9 的根 crate 没有直接声明 tokio，必须使用 hbb_common 的公开重导出。
// 新版本应先检查 Cargo.toml；不要直接写 tokio::... 后假设它一定是直接依赖。
use hbb_common::tokio;
use std::os::{fd::FromRawFd, unix::net::UnixStream as StdUnixStream};

let stream = unsafe { StdUnixStream::from_raw_fd(fd) };
stream.set_nonblocking(true)?;
let stream = tokio::net::UnixStream::from_std(stream)?;
```

`ParcelFileDescriptor.createSocketPair()` 创建的是 **AF_UNIX socketpair**，绝不能包装成 `std::net::TcpStream` 或 `tokio::net::TcpStream`。

在 Rust 2018/2021 中，传递依赖不会自动成为当前 crate 可直接引用的名称。若根 `Cargo.toml` 没有直接依赖 `tokio`，`tokio::task`/`tokio::net` 会报 `E0433`；应优先使用项目已有的 `hbb_common::tokio` 重导出，而不是为了两行代码再添加一份可能版本冲突的 Tokio 依赖。

历史错误把它当 TCP 后：

- TLS 日志已经显示 handshake completed；
- UI 仍提示 `invalid argument`；
- Rust 在 TCP `local_addr()` 上得到 `EINVAL`；
- 无法发送 RustDesk 协议数据。

1.4.9 最终用 `127.0.0.1:0` 作为 `FramedStream` 的内部地址元数据。该地址不是实际网络端点。

虽然外层 enum variant 在 1.4.9 仍叫 `Stream::Tcp`，内部 I/O 可以是 UnixStream；新版若抽象改变，应使用语义等价的 framed/raw stream 适配，不要仅因 enum 名称误判底层 fd。

### 7.8 R8/ProGuard

release APK 会执行 shrink/obfuscation，JNI 动态 class load 无法被 R8 静态分析。必须保留：

```proguard
# The Rust mTLS transport loads this Kotlin bridge dynamically through JNI.
# Keep both its name and members: R8 cannot infer the native call site.
-keep class com.carriez.flutter_hbb.AndroidMtlsSocketBridge { *; }
```

缺少它会在 debug 或未 shrink 构建中正常，但 release APK 在选择证书后第一次连接时闪退或报 `ClassNotFoundException`。

### 7.9 Gradle

1.4.9 在 `flutter/android/gradle.properties` 增加：

```properties
org.gradle.caching=true
```

这是构建优化，不是 mTLS 功能依赖。新版本若官方已经管理 Gradle caching，可省略重复设置。

---

## 8. `Cargo.lock` 与 hbb_common 补丁

即使新增依赖只在 Windows target 下声明，Cargo lockfile 仍必须与修改后的 hbb_common manifest 对齐。1.4.9 的根 `Cargo.lock` 在 hbb_common package dependency list 中新增：

```text
mio 0.8.11
schannel
x509-parser
```

新版迁移流程：

1. 在干净、递归检出的 hbb_common 上完成修改。
2. 如果它是子模块，生成包含新文件的父仓库补丁。
3. 临时应用补丁。
4. 运行最小范围 Cargo 命令更新 lockfile；不要带 `--locked`。
5. 检查 lock diff，只应包含本功能必要变化。
6. 反向应用补丁，让子模块工作树恢复干净。
7. 从干净子模块再次验证：

```bash
git -C libs/hbb_common apply --check ../../.github/patches/hbb_common_windows_mtls.diff
```

生成包含 untracked 新文件的 patch 时，可先用 intent-to-add：

```bash
git -C libs/hbb_common add -N src/mtls_windows.rs
git -C libs/hbb_common diff --binary -- \
  Cargo.toml src/lib.rs src/socket_client.rs src/mtls_windows.rs \
  > .github/patches/hbb_common_windows_mtls.diff
git -C libs/hbb_common reset
```

然后验证补丁并清理子模块。不要提交子模块脏状态。

Android workflow 也应应用同一 hbb_common 补丁，使两平台 checkout 使用同一 manifest/lock graph；Windows-only module 由 `cfg(target_os = "windows")` 隔离。

---

## 9. Flutter bridge：为冷构建固化生成文件

### 9.1 原因

RustDesk 1.4.9 默认忽略以下生成文件：

```text
src/bridge_generated.rs
src/bridge_generated.io.rs
flutter/lib/generated_bridge.dart
flutter/lib/generated_bridge.freezed.dart
```

如果每次工作流都先在 Ubuntu 安装 Rust、Flutter、`cargo-expand` 和 `flutter_rust_bridge_codegen` 再生成 bridge，完全冷缓存时仅这一步就可能耗时十几分钟。

用户只会在 RustDesk 正式 Release 后偶尔构建。GitHub Actions 默认缓存长时间未访问会失效，因此最终方案：

- 针对每个新 Release 一次性重新生成四个 bridge 文件；
- 把四个文件提交到该 Release 的定制仓库；
- 从 `.gitignore` 和 `flutter/.gitignore` 删除对应 ignore 项；
- 最终 Windows/Android workflow 不再包含 `generate-bridge` job；
- 不使用 Actions cache 保存 bridge、Rust target、Gradle 或 vcpkg binary。

### 9.2 新版必须重新生成

绝不能把 1.4.9 的 bridge 文件复制到新版本。它们必须匹配：

- 新版 `src/flutter_ffi.rs`；
- 新版 Cargo feature/API；
- 新版 `flutter_rust_bridge`；
- 新版 Flutter/Dart dependencies。

先查新版官方脚本或 workflow。1.4.9 仅供参考：

```bash
cargo install cargo-expand --version 1.0.95 --locked
cargo install flutter_rust_bridge_codegen \
  --version 1.80.1 --features uuid --locked

pushd flutter
flutter pub get
popd

flutter_rust_bridge_codegen \
  --rust-input ./src/flutter_ffi.rs \
  --dart-output ./flutter/lib/generated_bridge.dart
```

1.4.9 曾针对 `extended_text` 做版本替换；新版本不要盲目保留该 workaround。

生成后检查 Rust/Dart 文件头中的 codegen 版本一致，再提交。若新版改用 FRB 2.x 或不同文件名，应按新版官方生成方式处理。

可以使用一个临时手动 workflow 生成并下载这四个文件，但最终必须删除临时 workflow，只留下两个交付工作流。

---

## 10. 最终 Actions 设计

### 10.1 通用要求

两个文件：

```text
.github/workflows/windows-dll.yml
.github/workflows/android-apk.yml
```

Actions UI 名称：

```text
Build Windows x64 DLL
Build Android ARM64 APK
```

触发器只有：

```yaml
on:
  workflow_dispatch:
    inputs:
      version:
        description: Artifact version label
        required: true
        default: "<RUSTDESK_VERSION>"
        type: string
```

还应：

- `permissions: contents: read`；
- 两个平台使用不同 concurrency group；
- `cancel-in-progress: false`；
- Actions 全部 pin 到审核过的完整 commit SHA；
- 从新版本官方 workflow 取得兼容的 toolchain/action 版本；
- 不添加 `push`、`pull_request`、tag 或 schedule 触发；
- 不创建 Release；
- artifact `if-no-files-found: error`；
- artifact 可设置 90 天保留；
- APK/DLL 已经是二进制，`compression-level: 0` 可减少上传 CPU 时间；
- 不依赖“热缓存”作为性能目标。
- 在阶段 A 的所有代码、bridge、锁文件和 workflow 完成前不得手动 dispatch。
- 每次最终 run 必须能追溯到包含全部实现的同一个 `<FINAL_COMMIT_SHA>`。

删除 `.github/workflows/` 下除这两个文件以外的其他 workflow 文件。删除前先确认范围，不能删除 `.github` 下的补丁、issue template 等非 workflow 文件。

GitHub 可能额外显示系统生成的 `Dependency Graph` 任务；它不对应仓库 workflow 文件，也不是 Windows/Android 产品构建。

### 10.2 Windows DLL-only workflow

Windows workflow 的必要步骤：

1. `windows-2022` 或新版本官方已验证 runner。
2. checkout，`submodules: recursive`。
3. apply hbb_common Windows mTLS patch。
4. 安装与新版本匹配的 LLVM。
5. 安装与新版本匹配的 Rust/MSVC target。
6. setup 与新版本匹配的 vcpkg commit。
7. 安装 `x64-windows-static` 依赖。
8. 直接构建 Rust library，不调用会继续构建 Flutter/portable 的 `build.py`：

```powershell
cargo build --locked --release --lib --features "<NEW_VERSION_OFFICIAL_FEATURES>"
```

1.4.9 features：

```text
flutter,hwcodec,vram
```

9. 明确检查：

```powershell
Test-Path target\release\librustdesk.dll
Get-FileHash target\release\librustdesk.dll -Algorithm SHA256
```

10. 上传：

```text
artifact name: librustdesk-<version>-windows-x64
artifact content: target/release/librustdesk.dll
```

必须删除：

- Flutter SDK 安装；
- RustDesk Flutter engine 下载；
- Flutter framework patch；
- `flutter build windows`；
- virtual display helper 构建；
- TopMost/WindowInjection helper；
- USB/printer resources；
- portable/self-extracting EXE；
- MSBuild（若 vcpkg/新依赖不需要单独 setup）；
- Rust target cache；
- vcpkg binary Actions cache；
- bridge generation job。

注意：不能去掉 Rust 的 Flutter feature。官方 Flutter EXE 通过 FFI 加载 `librustdesk.dll`；不带该 feature 会缺导出符号。固定的 Rust bridge 生成文件也因此仍然需要。

若不用 vcpkg binary cache，可设置：

```yaml
VCPKG_BINARY_SOURCES: "clear"
```

### 10.3 Android ARM64 workflow

Android workflow 的必要步骤：

1. Ubuntu runner，按需要释放磁盘。
2. 安装新版官方 workflow 使用的系统编译依赖和 JDK。
3. checkout recursive submodules。
4. apply 同一 hbb_common patch。
5. 安装新版匹配的 Flutter 并应用仍然必要的官方 patch。
6. 安装新版匹配的 Android NDK。
7. setup vcpkg，并构建 `arm64-v8a` 原生依赖。
8. 安装 Rust target `aarch64-linux-android`。
9. 安装新版匹配的 `cargo-ndk`。
10. 调用新版等价的 ARM64 Rust build script。
11. 把：

```text
liblibrustdesk.so
libc++_shared.so
```

复制到：

```text
flutter/android/app/src/main/jniLibs/arm64-v8a/
```

12. 构建单 ABI release APK。
13. 当前方案把 release signing 临时改为 debug signing；若新版 Gradle 文件已变化，按新版语法适配。
14. 上传：

```text
artifact name: rustdesk-<version>-android-arm64
artifact content: rustdesk-<version>-android-arm64.apk
```

最终不需要：

- bridge generation job；
- Gradle Actions cache；
- Rust target Actions cache；
- vcpkg binary Actions cache；
- GitHub Release 发布步骤；
- 其他 ABI。

如用户要求 APK 可直接覆盖安装，应改为使用受保护的稳定签名 secret；不得把 keystore 或密码提交仓库。本教程的基础方案不包含稳定生产签名。

### 10.4 默认分支注册问题

`workflow_dispatch` 工作流必须存在于 GitHub 默认分支才能在 Actions UI 注册。

最终必须确认两个工作流文件已经提交到 GitHub 默认分支。本文将该分支参数化为：

```text
<DEFAULT_BRANCH>
```

如果代码已推送但 Actions 页面仍显示旧 workflow：

1. 用 GitHub CLI/API 检查默认分支；
2. 把两个新 workflow 提交到当前默认分支，或在明确获得授权后把正确的定制分支设为默认分支；
3. 再检查 workflow list；
4. 旧工作流名称可能仍出现在历史 run 中，这是历史记录，不代表仍可运行。

### 10.5 构建环境契约与快速失败检查

构建脚本不能只写“安装依赖然后运行 Cargo”。跨平台 C/C++ 依赖会同时区分：

- GitHub runner 的 host 架构；
- vcpkg host triplet；
- vcpkg target triplet；
- Rust target；
- Android NDK 版本、sysroot 和 API level；
- Flutter 最终打包 ABI。

这些值只要有一处隐式回落到 runner 默认值，就可能先成功编译很久，最后才报一个容易误导的缺失头文件。

#### Windows：host triplet 也必须是 static

仅向 vcpkg 传入：

```bash
--triplet x64-windows-static
```

并不足以保证 manifest 中标记为 host 的依赖也安装到 static triplet。1.4.9 的 DLL-only 工作流必须在 vcpkg install 步骤同时设置：

```yaml
env:
  VCPKG_DEFAULT_HOST_TRIPLET: x64-windows-static
```

否则可能出现：

- `aom`、`libvpx` 等同时安装 dynamic/static；
- `ffmpeg` 只安装到 `x64-windows`；
- `hwcodec` 却从 `x64-windows-static/include` 查找；
- Cargo 运行数分钟后才报 `libavcodec/avcodec.h` 或 `libavutil/pixfmt.h` 不存在。

安装后、启动 Cargo 前必须快速验证：

```powershell
$include = Join-Path $env:VCPKG_ROOT "installed\x64-windows-static\include"
if (-not (Test-Path (Join-Path $include "libavcodec\avcodec.h"))) {
    throw "ffmpeg headers are missing from x64-windows-static"
}
if (-not (Test-Path (Join-Path $include "libavutil\pixfmt.h"))) {
    throw "libavutil headers are missing from x64-windows-static"
}
```

显式 Rust toolchain 可以增加 `components: rustfmt`。某些 bindgen/build script 会尝试调用 rustfmt；缺少它通常不是主失败原因，但会制造大量噪音并妨碍日志审计。

#### Android：三个 NDK 变量必须指向同一路径

优先复用目标 Release 官方工作流所用并已 pin 的 NDK setup action，不要自行假设 `sdkmanager` 已在 `PATH`。NDK 安装步骤应提供单一可信路径，Rust/native build 步骤同时设置：

```yaml
env:
  ANDROID_NDK_HOME: ${{ steps.setup-ndk.outputs.ndk-path }}
  ANDROID_NDK_ROOT: ${{ steps.setup-ndk.outputs.ndk-path }}
  ANDROID_NDK: ${{ steps.setup-ndk.outputs.ndk-path }}
```

启动 vcpkg/cargo-ndk 前验证：

```bash
test "$ANDROID_NDK_HOME" = "$ANDROID_NDK_ROOT"
test "$ANDROID_NDK_HOME" = "$ANDROID_NDK"
test -x "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang"
```

日志中若出现“`ANDROID_NDK_HOME` 与 `ANDROID_NDK_ROOT` 指向不同 NDK”的警告，必须立即失败并修正，不能继续等待后续编译碰运气。

#### 不要从 Ubuntu 包名推断 APK 架构

`gcc-multilib`、`g++-multilib`、`libc6-dev-i386` 是 Ubuntu runner 上供 host 编译工具/build script 使用的包；它们不会把：

```text
aarch64-linux-android
```

改成 Android i386，也不会自动向 APK 加入 x86 ABI。最终 ABI 由 Rust target、cargo-ndk target、JNI 目录和 Flutter target 共同决定。

但这不表示可以看到缺失头文件就盲目安装 `libc6-dev-i386`。正确顺序是：

1. 对照同一 Release 的官方 Linux/Android 依赖列表；1.4.9 官方工作流使用的是 `gcc-multilib`/`g++-multilib`，后者可能间接带入 32 位 glibc 开发头；
2. 先消除 NDK 环境变量冲突；
3. 确认失败来自 host 侧 build script，还是应使用 Android sysroot 的 target bindgen；
4. 检查失败命令的 target、sysroot 和 include search path；
5. 只有确认属于官方已验证的 host 构建依赖后，才保留 multilib 包，并在 workflow 中写注释解释用途；
6. 如果 bindgen 本应解析 Android 头文件，则按该版本 bindgen/hwcodec 的实际行为设置 target-specific `BINDGEN_EXTRA_CLANG_ARGS`，例如 Android target 与 NDK sysroot；不能用 host glibc 头文件掩盖错误 target。

看到：

```text
/usr/include/stdint.h: fatal error: bits/libc-header-start.h not found
```

时，缺失的文件只是表象。先回答“为什么 Android ARM64 bindgen 进入了 host `/usr/include`”，再决定是否安装 host multilib 依赖。

APK 生成后还要检查内容，而不是凭工作流名称判断：

```bash
unzip -l rustdesk-*-android-arm64.apk | grep 'lib/'
```

基础方案应只出现 `lib/arm64-v8a/` 下的目标原生库，不应意外包含 `x86`、`x86_64` 或 `armeabi-v7a`。

---

## 11. 推荐实施顺序

按以下顺序工作，便于隔离故障：

1. 验证官方 Release 源码、版本和子模块。
2. 记录新版官方工具链/build 参数。
3. 删除无关 workflow，创建两个新的 manual workflow 框架，但暂不触发。
4. 增加统一的 `connect_rustdesk_service` 抽象。
5. 逐个迁移 HBBS/HBBR TCP 调用点；重新审计遗漏和误替换。
6. 实现 Windows hbb_common patch。
7. 临时应用 patch，最小更新 `Cargo.lock`，再恢复干净子模块。
8. 实现 Android credential manager、Activity chooser、SSLSocket bridge、JNI/context helper、UnixStream adapter 和 ProGuard keep。
9. 运行 Rust format 只针对相关 Rust 文件；不要格式化整个仓库。
10. 按新版方式一次性生成四个 Flutter bridge 文件并纳入 Git。
11. 完成 DLL-only Windows workflow。
12. 完成 ARM64 APK Android workflow。
13. 用 `actionlint` 检查两个 YAML。
14. 用 `git diff -w` 审查是否存在换行符/格式噪音。
15. 确认没有证书、P12、私钥、keystore、密码、ADB 分发文件、构建缓存或子模块脏状态。
16. 精确提交并推送到 `<DEFAULT_BRANCH>`。
17. 记录 `<FINAL_COMMIT_SHA>`，确认工作区没有遗漏的实现文件。
18. 确认两个 workflow 已在默认分支注册。
19. 到此才通过阶段 A，手动触发两个 workflow。
20. 核对两个 run 的 `headSha == <FINAL_COMMIT_SHA>`。
21. 监控到两个 artifact 成功；失败时读取完整 job logs，做最小修复、产生新的最终 commit，并重新手动触发。
22. 下载并检查两个 artifact。
23. 明确报告“等待用户实机验证”，不要宣布任务完成。
24. 只有用户确认 Windows 和 Android 真实功能均通过后，才宣布迁移完成。

---

## 12. 静态检查与 Actions 操作

建议检查：

```bash
git status --short
git diff --check
git diff -w --stat
git -C libs/hbb_common status --short
git -C libs/hbb_common apply --check \
  ../../.github/patches/hbb_common_windows_mtls.diff
```

工作流检查：

```bash
actionlint .github/workflows/windows-dll.yml
actionlint .github/workflows/android-apk.yml
```

只有通过“阶段 A：实现与静态审计”后，才能使用以下手动触发命令：

```bash
gh workflow run windows-dll.yml \
  --repo <OWNER>/<REPO> \
  --ref <DEFAULT_BRANCH> \
  -f version=<RUSTDESK_VERSION>

gh workflow run android-apk.yml \
  --repo <OWNER>/<REPO> \
  --ref <DEFAULT_BRANCH> \
  -f version=<RUSTDESK_VERSION>
```

查看：

```bash
gh run list --repo <OWNER>/<REPO> --limit 10
gh run view <RUN_ID> --repo <OWNER>/<REPO> --log-failed
```

不要运行发布 Release 的命令。

### 12.1 Actions 失败审计与重跑纪律

每次运行都维护一条简短账本：

| 字段 | 内容 |
| --- | --- |
| Run | URL/ID |
| Platform | Windows 或 Android |
| `headSha` | 本次实际构建的完整 commit |
| First root cause | 完整日志中第一个可操作根因 |
| Adjacent checks | 同一步骤内还能静态确认的相邻问题 |
| Fix evidence | 为什么本次修改能修复根因 |
| Artifact | 名称、架构、SHA256，或明确写“无” |

失败后：

1. 读取完整失败步骤，不要只看 UI 最后一行或第一个 `not found`。
2. 查看出错源码/脚本及目标版本官方工作流。
3. 在提交前把同一层的低成本检查一次做完。例如 workflow 初始化失败时，应同时核对 NDK 安装、`nasm`、签名、vcpkg host triplet 和 artifact 路径。
4. 一次 focused commit 可以修复多个已经有证据的相邻问题；“每看到一行错误就加一个包并重跑”不是严谨的最小修复。
5. 不要用 Actions 逐行替代静态审计。缺模块文件、Kotlin 明显语法错误、未配置签名和错误 triplet 都应在再次 dispatch 前发现。
6. 修复只影响一个平台时，先只跑受影响平台的诊断构建；不要取消另一个仍有价值的运行。
7. 两个平台分别达到绿色后，冻结最终 SHA，再从同一最终 SHA 运行两个交付构建并保留 artifact。
8. `cancel-in-progress: false` 会让重复 dispatch 排队。前一运行仍在进行时，不要无意义地再点一次；若它对应已知废弃 SHA，明确取消并在账本记录原因。

一次 Actions 运行失败并不自动说明“提前构建”：全部代码完成后仍可能遇到 runner/toolchain 差异。真正的违规信号是运行对应的 commit 明知还缺必要模块、存在占位实现，或 workflow 仍未包含官方所需构建阶段。

---

## 13. 功能验收矩阵

### 13.1 Windows

准备：

- 官方同版本 Windows x64 客户端；
- 定制构建的 `librustdesk.dll`；
- Current User `My` 中恰好一张合格客户端证书；
- 已设置信任 nginx 服务端证书链；
- RustDesk ID/relay 设置使用 DNS 入口。

正向测试：

1. 停止 RustDesk UI 和服务。
2. 备份并替换 DLL。
3. 启动客户端。
4. 确认能向 HBBS 注册并获得/显示 ID。
5. 建立 relay 会话。
6. 持续观察画面。
7. 高频移动鼠标并输入键盘。
8. 测试文件传输/剪贴板等长连接数据。
9. 保持会话一段时间，确认无“假丢包/高延迟”。
10. 重连。

负向测试：

- 0 张合格证书；
- 2 张合格证书；
- 证书过期；
- 无私钥；
- OU 不符；
- EKU 不符；
- 服务端证书不可信；
- 服务端证书 hostname 不匹配；
- 把服务端改成 IP；
- 启用 SOCKS。

所有负向条件都必须失败且不得明文回退。

### 13.2 Android

准备：

- ARM64 真机，API 29+；
- 系统中已导入客户端 P12；
- 已设置 RustDesk ID/relay DNS；
- 如 APK 签名不同，先卸载旧版。

正向测试：

1. 安装 APK。
2. 启动并设置服务器。
3. 在设置页点击 `mTLS client certificate`。
4. 在系统 chooser 中选择证书。
5. 确认出现成功提示。
6. 建立连接。
7. 测试画面、鼠标、键盘和传输。
8. 断开并重连。
9. 保持空闲后再次发送数据，确认没有因握手 timeout 残留而断开。

负向测试：

- 取消 chooser；
- 选择 OU/EKU 不符的证书；
- 删除已选 alias；
- 撤销 KeyChain 访问；
- 服务端 hostname/chain 错误；
- 服务端设置为空或使用 IP。

失败后 alias 应被清除，用户需要重新选择；不得闪退或明文回退。

---

## 14. Android 真机诊断

ADB 二进制可能由用户临时放在 `platform-tools/`；它不属于源码，绝不能提交。

清日志：

```powershell
.\platform-tools\adb.exe logcat -c
```

复现一次后：

```powershell
$appPid = (& .\platform-tools\adb.exe shell pidof com.carriez.flutter_hbb).Trim()
.\platform-tools\adb.exe logcat -d -v threadtime --pid=$appPid
```

按 tag 过滤：

```powershell
.\platform-tools\adb.exe logcat -d -v threadtime -s RustDeskMtls
```

必要时用：

```powershell
.\platform-tools\adb.exe shell su -c id
```

确认 shell/root 是否可用，但正常 app mTLS 调试不应依赖 root。

重点观察：

- `Opening mTLS connection`；
- `mTLS handshake completed`；
- `ClassNotFoundException`；
- `invalid argument`/`EINVAL`；
- Java exception stack；
- pump 方向和字节数；
- Rust panic/backtrace；
- R8 类是否存在。

不要只看 UI 的通用错误字符串。UI 曾只显示 `invalid argument`，真正原因必须结合 JNI/Kotlin/Rust 日志定位。

---

## 15. 历史故障与不可回退的修复

| 表现 | 根因 | 必须保留的修复 |
| --- | --- | --- |
| Windows 能连接但画面/输入极度延迟，像丢包 | SChannel 只处理 readable 或 worker wake/poll 状态不完整 | handshake 同时 read/write；动态 writable interest；mio Waker；部分写处理 |
| Windows 客户端未真正发送证书 | 只获取了空 SChannel credential | `SchannelCred::builder().cert(certificate).acquire(Direction::Outbound)` |
| Windows poll 编译借用错误 | poll/tls mutability 不正确 | 根据 `register/reregister/poll` 实际签名保留正确 `mut` |
| Android 选择证书后连接时闪退 | native thread 直接 `FindClass` | application context 的 class loader `loadClass` |
| Android release APK 才闪退/ClassNotFound | R8 删除或重命名 bridge | ProGuard `-keep AndroidMtlsSocketBridge` |
| Android TLS handshake 完成但 UI 显示 `invalid argument` | AF_UNIX socketpair 被包装成 TcpStream | `StdUnixStream` -> `tokio::net::UnixStream` |
| Android 长连接空闲后异常关闭 | handshake timeout 没有清除 | handshake 后 `soTimeout = 0` |
| Android OU 判断错误 | 用普通逗号 split RFC2253 subject | 识别转义的 RDN 分隔 |
| Android KeyChain 访问卡 UI | 在 UI/Tokio worker 直接做阻塞调用 | credential executor + `spawn_blocking` |
| Android 取消/删除证书后仍反复使用旧 alias | 失败时没有清 SharedPreferences | 所有 credential 加载/验证失败都 clear alias |
| Actions 新 workflow 文件存在但 UI 看不到 | workflow 不在 GitHub 默认分支 | 将文件提交到默认分支，或经授权调整默认分支 |
| Actions 构建成功但找不到产物 | workflow 试图发布 Release 或 artifact 路径错误 | 只用 `upload-artifact`，并设置 `if-no-files-found: error` |
| AI 在代码未写完时触发构建并宣布完成 | 把成功 dispatch/编译误当成功能交付 | 阶段 A 完整审计后才构建；核对最终 SHA；两个 artifact 和用户双平台实测缺一不可 |
| Windows `mod mtls_windows` 后报模块文件不存在 | 父仓库补丁只包含 `mod`/调用点，漏掉 untracked 新文件 | 用 `git add -N` 生成包含新文件的补丁；从干净子模块应用后确认文件存在 |
| Windows 修完一个借用错误后又缺 FFmpeg 头 | vcpkg target 是 static，但 host 依赖仍落到 `x64-windows` | 设置 `VCPKG_DEFAULT_HOST_TRIPLET=x64-windows-static`，Cargo 前检查 FFmpeg 头 |
| Windows 日志反复提示找不到 rustfmt | 显式旧 Rust toolchain 未安装 rustfmt component | toolchain 增加 `components: rustfmt`，并区分该噪音与真正编译失败 |
| Android 一开始找不到 `sdkmanager` | 假设 runner PATH，而未复用官方 NDK setup | 使用同版本官方、pin 到 SHA 的 NDK action及其输出路径 |
| Android vcpkg 报找不到 `nasm` | 从头猜测了过小的系统依赖列表 | 先移植同 Release 官方依赖列表，再有证据地精简 |
| Android Kotlin expression body 报禁止 `return` | 业务代码尚未通过基本 Kotlin 编译检查就触发最终构建 | 改为 block body，并在 dispatch 前运行/审计 `compileReleaseKotlin` |
| Android `packageRelease` 缺 `storeFile` | release signing 仍引用未配置的 release keystore | 基础方案显式使用 debug signing；生产签名使用受保护 secrets |
| Android ARM64 构建出现 host glibc `bits/*` 缺失 | hwcodec bindgen 的 host/target include 与 NDK 环境未审清 | 先统一 NDK 变量并检查 bindgen target/sysroot；不要直接用 `libc6-dev-i386` 掩盖 |
| Android Rust 报 `use of undeclared crate or module tokio` | 把传递依赖误当成根 crate 的直接依赖 | 检查 manifest；1.4.9 使用 `use hbb_common::tokio` 的公开重导出 |
| Android pump 从临时 `dup().fileDescriptor` 建流 | dup 后的 `ParcelFileDescriptor` 所有者立即丢失，fd 生命周期不明确 | 使用 `AutoCloseInputStream/AutoCloseOutputStream` 或显式保留两个 PFD 所有者 |
| Actions 连续产生多个“一行修复、一次冷构建” | 没有阅读完整日志和审计相邻环境契约 | 维护 run 账本；一次检查同层问题；受影响平台先诊断，最终 SHA 再双平台交付 |
| 冷构建 bridge 耗时十几分钟 | 每次安装 Flutter/codegen 生成派生文件 | 每个 Release 一次生成四文件并提交 |
| Windows 构建仍很久 | 继续构建 Flutter UI、helpers、portable EXE | 直接 `cargo build --lib`，只上传 DLL |

---

## 16. 1.4.9 语义文件变更参考

这不是要求新版本逐字相同，而是用于审计遗漏。

### 新增

```text
.github/patches/hbb_common_windows_mtls.diff
.github/workflows/windows-dll.yml
.github/workflows/android-apk.yml
AI_MIGRATION_GUIDE.md
flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/AndroidMtlsSocketBridge.kt
flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/MtlsCredentialManager.kt
src/android_mtls.rs
src/bridge_generated.rs
src/bridge_generated.io.rs
flutter/lib/generated_bridge.dart
flutter/lib/generated_bridge.freezed.dart
```

### 最小语义修改

```text
.gitignore
flutter/.gitignore
Cargo.lock
flutter/android/app/proguard-rules
flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/MainActivity.kt
flutter/android/gradle.properties
flutter/lib/mobile/pages/settings_page.dart
libs/scrap/src/android/ffi.rs
src/client.rs
src/common.rs
src/lib.rs
src/rendezvous_mediator.rs
src/server.rs
src/ui_interface.rs
```

### 删除

`.github/workflows/` 下除两个最终工作流外的所有上游/旧 workflow。

### 通常不需要产生语义修改

```text
根 Cargo.toml
src/flutter_ffi.rs
src/platform/windows.rs
MainService.kt
flutter/android/app/src/main/kotlin/ffi.kt
```

新版结构确实要求时可以修改，但必须能解释原因，不能复制 1.4.9 的换行符噪音。

---

## 17. 完成定义

只有同时满足以下条件才能向用户报告完成：

- [ ] 新版本官方基线和子模块来源已核对。
- [ ] Windows/Android 的证书与安全失败规则实现。
- [ ] 所有 HBBS/HBBR TCP 服务调用点已迁移并重新审计。
- [ ] 非目标 TCP/UDP/WebSocket/WebRTC/IPC 路径保持上游行为。
- [ ] hbb_common patch 能从干净子模块 `apply --check`。
- [ ] `Cargo.lock` 与应用 patch 后的 manifest 一致。
- [ ] 四个 bridge 文件由新版本重新生成并纳入 Git。
- [ ] 没有 TODO、占位实现、临时 workflow 或待补写的平台代码。
- [ ] 最终 `.github/workflows/` 只有 Windows DLL 和 Android APK 两个文件。
- [ ] 两个 workflow 只有 `workflow_dispatch`。
- [ ] 两个 workflow 文件存在于 GitHub 默认分支并已注册。
- [ ] Windows vcpkg target/host triplet 都是预期 static triplet，必要 FFmpeg 头在 Cargo 前已验证。
- [ ] Android `ANDROID_NDK_HOME`、`ANDROID_NDK_ROOT`、`ANDROID_NDK` 完全一致，日志没有 NDK 版本冲突。
- [ ] Android host multilib 依赖有官方依据和用途注释，没有用 i386 包名冒充 Android target 修复。
- [ ] 已记录 `<FINAL_COMMIT_SHA>`，两个最终 Actions run 的 `headSha` 均与之完全一致。
- [ ] Windows Actions 成功，artifact 内只有正确的 `librustdesk.dll`。
- [ ] Android Actions 成功，artifact 内有 ARM64 APK。
- [ ] 没有创建 GitHub Release。
- [ ] 没有提交任何证书、私钥、P12、keystore、密码、ADB、缓存或构建产物。
- [ ] Windows 真机测试通过，尤其是持续画面和输入响应。
- [ ] Android ARM64 真机选择证书、连接、远控、空闲后继续、断开重连均通过。
- [ ] 负向证书/hostname 测试安全失败且没有明文回退。
- [ ] 用户已明确确认两个平台的最终产物功能正常；不能仅凭 Actions 成功勾选此项。

核心原则：

> 安全失败优于兼容性回退；系统托管私钥优于应用管理私钥；局部服务连接替换优于全局网络劫持；真实设备和持续数据流验证优于仅仅“编译成功”或“握手成功”。
