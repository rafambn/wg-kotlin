use super::*;
use std::os::windows::ffi::OsStrExt;
use std::os::windows::io::OwnedHandle;
use windows::core::PCWSTR;

#[test]
fn rejects_existing_directory_without_touching_its_dll() {
    let directory = std::env::temp_dir().join(format!("wg-kotlin-wintun-test-{:?}", unsafe { CoCreateGuid().unwrap() }));
    std::fs::create_dir(&directory).unwrap();
    let path = directory.join("wintun.dll");
    std::fs::write(&path, b"untrusted DLL").unwrap();
    assert!(WintunDll::extract_at(directory.clone()).is_err());
    assert_eq!(std::fs::read(&path).unwrap(), b"untrusted DLL");
    std::fs::remove_file(path).unwrap();
    std::fs::remove_dir(directory).unwrap();
}

#[test]
#[ignore = "requires an elevated Windows process; creates no TUN or driver"]
fn prevents_file_and_directory_replacement_until_released() {
    use windows::Win32::Foundation::FreeLibrary;
    use windows::Win32::System::LibraryLoader::{GetProcAddress, LoadLibraryW};

    let dll = WintunDll::prepare().unwrap();
    let path = dll.path().to_path_buf();
    let directory = path.parent().unwrap().to_path_buf();
    assert_eq!(std::fs::read(&path).unwrap(), include_bytes!("../../resources/wintun-x64.dll").as_slice());
    assert!(OpenOptions::new().write(true).open(&path).is_err());
    assert!(std::fs::remove_file(&path).is_err());
    assert!(std::fs::rename(&path, directory.join("replaced.dll")).is_err());
    assert!(std::fs::rename(&directory, directory.with_extension("replaced")).is_err());
    let path_wide: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
    let module = unsafe { LoadLibraryW(PCWSTR(path_wide.as_ptr())).unwrap() };
    let has_api = unsafe { GetProcAddress(module, windows::core::s!("WintunCreateAdapter")).is_some() };
    unsafe { FreeLibrary(module).unwrap() };
    assert!(has_api);
    drop(dll);
    assert!(!path.exists());
    assert!(!directory.exists());
}

#[test]
#[ignore = "requires an elevated Windows UAC account with a linked unelevated token"]
fn unelevated_owner_cannot_rewrite_the_file_or_its_permissions() {
    use windows::Win32::Security::{GetTokenInformation, ImpersonateLoggedOnUser, RevertToSelf, TOKEN_LINKED_TOKEN, TOKEN_QUERY, TokenLinkedToken};
    use windows::Win32::Storage::FileSystem::WRITE_DAC;
    use windows::Win32::System::Threading::{GetCurrentProcess, OpenProcessToken};

    let dll = WintunDll::prepare().unwrap();
    let mut token = HANDLE::default();
    unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token).unwrap() };
    let token = unsafe { OwnedHandle::from_raw_handle(token.0) };
    let mut linked = TOKEN_LINKED_TOKEN::default();
    let mut returned = 0;
    unsafe {
        GetTokenInformation(
            HANDLE(token.as_raw_handle()),
            TokenLinkedToken,
            Some((&mut linked as *mut TOKEN_LINKED_TOKEN).cast()),
            std::mem::size_of_val(&linked) as u32,
            &mut returned,
        )
        .unwrap();
    }
    let linked = unsafe { OwnedHandle::from_raw_handle(linked.LinkedToken.0) };
    unsafe { ImpersonateLoggedOnUser(HANDLE(linked.as_raw_handle())).unwrap() };
    let read = File::open(dll.path());
    let write = OpenOptions::new().write(true).open(dll.path());
    let file_acl = OpenOptions::new().access_mode(WRITE_DAC.0).open(dll.path());
    let directory_acl = OpenOptions::new().access_mode(WRITE_DAC.0).custom_flags(FILE_FLAG_BACKUP_SEMANTICS.0).open(dll.path().parent().unwrap());
    unsafe { RevertToSelf().unwrap() };
    assert!(read.is_err());
    assert!(write.is_err());
    assert!(file_acl.is_err(), "the unelevated account must not have owner WRITE_DAC rights");
    assert!(directory_acl.is_err(), "the unelevated account must not own the directory");
}
