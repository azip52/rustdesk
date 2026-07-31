use hbb_common::{
    anyhow::{anyhow, Context as _},
    bail,
    tcp::FramedStream,
    tokio,
    ResultType, Stream,
};
use jni::objects::{JClass, JObject, JValue};
use std::{
    net::IpAddr,
    os::{fd::FromRawFd, unix::net::UnixStream as StdUnixStream},
};

fn parse_target(target: &str) -> ResultType<(&str, u16)> {
    let (host, port) = target
        .rsplit_once(':')
        .context("Android mTLS endpoint must be a DNS host and port")?;
    if host.is_empty() || host.contains(':') || host.parse::<IpAddr>().is_ok() {
        bail!("Android mTLS service endpoint must use a DNS hostname");
    }
    let port: u16 = port.parse().context("Invalid Android mTLS service port")?;
    if port == 0 {
        bail!("Android mTLS service port must not be zero");
    }
    Ok((host, port))
}

/// Opens the KeyChain-backed Android TLS transport and adapts its AF_UNIX
/// socketpair endpoint to RustDesk's framed stream abstraction.
pub async fn connect_service(target: String, timeout_ms: u64) -> ResultType<Stream> {
    let (host, port) = parse_target(&target)?;
    let host = host.to_owned();
    let fd = tokio::task::spawn_blocking(move || {
        scrap::android::ffi::with_java_vm_and_application_context(|vm, context| -> ResultType<i32> {
            let mut env = vm.attach_current_thread()?;
            let loader = env
                .call_method(context.as_obj(), "getClassLoader", "()Ljava/lang/ClassLoader;", &[])?.l()?;
            let class_name = env.new_string("com.carriez.flutter_hbb.AndroidMtlsSocketBridge")?;
            let class = env.call_method(
                loader,
                "loadClass",
                "(Ljava/lang/String;)Ljava/lang/Class;",
                &[JValue::Object(&JObject::from(class_name))],
            )?.l()?;
            let host = env.new_string(host)?;
            let fd = env.call_static_method(
                JClass::from(class),
                "open",
                "(Landroid/content/Context;Ljava/lang/String;II)I",
                &[
                    JValue::Object(context.as_obj()),
                    JValue::Object(&JObject::from(host)),
                    JValue::Int(port.into()),
                    JValue::Int(timeout_ms.min(i32::MAX as u64) as i32),
                ],
            )?.i()?;
            if fd < 0 {
                bail!("Android mTLS connection failed")
            }
            Ok(fd)
        })
        .ok_or_else(|| anyhow!("Android application context is unavailable"))?
    })
    .await
    .map_err(|error| anyhow!("Android mTLS worker failed: {error}"))??;

    // ParcelFileDescriptor.createSocketPair creates AF_UNIX, never TCP.
    let stream = unsafe { StdUnixStream::from_raw_fd(fd) };
    stream.set_nonblocking(true)?;
    let stream = tokio::net::UnixStream::from_std(stream)?;
    let addr = "127.0.0.1:0".parse()?;
    Ok(Stream::Tcp(FramedStream::from(stream, addr)))
}
