use super::*;
use windows::Win32::Foundation::{HANDLE, HLOCAL};
use windows::Win32::Security::Authorization::ConvertSidToStringSidW;
use windows::Win32::Security::*;
use windows::Win32::System::Threading::*;

// Preserve the SDK implementation independently of nt-token for native comparison.
fn previous_sid(process: HANDLE) -> anyhow::Result<String> {
    let mut token = HANDLE::default();
    unsafe { OpenProcessToken(process, TOKEN_QUERY, &mut token)? };
    let token = unsafe { windows::core::Owned::new(token) };
    let mut size = 0;
    let _ = unsafe { GetTokenInformation(*token, TokenUser, None, 0, &mut size) };
    let mut storage = vec![0_usize; (size as usize).div_ceil(std::mem::size_of::<usize>())];
    unsafe {
        GetTokenInformation(*token, TokenUser, Some(storage.as_mut_ptr().cast()), size, &mut size)?;
        let user = &*storage.as_ptr().cast::<TOKEN_USER>();
        let mut text = windows::core::PWSTR::null();
        ConvertSidToStringSidW(user.User.Sid, &mut text)?;
        let _allocation = windows::core::Owned::new(HLOCAL(text.0.cast()));
        Ok(text.to_string()?)
    }
}

#[test]
fn owner_user_sid_matches_sdk() {
    let process = unsafe {
        windows::core::Owned::new(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE, false, std::process::id()).unwrap())
    };
    let birth = windows_start_time(*process).unwrap();
    let owner = OwnerProcess::open(std::process::id(), birth / 10_000 - 11_644_473_600_000).unwrap();
    assert_eq!(owner.sid, previous_sid(*process).unwrap());
    assert_eq!(windows_start_time(*process).unwrap(), birth);
    assert!(owner.authorizes(std::process::id()));
}

#[test]
fn invalid_owner_pid_preserves_the_native_open_process_error() {
    let pid = u32::MAX;
    let current = OwnerProcess::open(pid, 0).err().expect("invalid PID must be rejected");
    let previous = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE, false, pid) }.unwrap_err();
    assert_eq!(current.downcast_ref::<windows::core::Error>().unwrap().code(), previous.code());
    assert_eq!(current.to_string(), previous.to_string());
}
