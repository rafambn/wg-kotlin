use anyhow::Context;
use std::ffi::OsString;
use std::fs::{File, OpenOptions};
use std::io::Write;
use std::os::windows::ffi::{OsStrExt, OsStringExt};
use std::os::windows::fs::{MetadataExt, OpenOptionsExt};
use std::os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle};
use std::path::{Path, PathBuf};
use windows::Win32::Foundation::{GENERIC_WRITE, HANDLE, HLOCAL, LocalFree};
use windows::Win32::Security::Authorization::ConvertStringSecurityDescriptorToSecurityDescriptorW;
use windows::Win32::Security::{PSECURITY_DESCRIPTOR, SECURITY_ATTRIBUTES};
use windows::Win32::Storage::FileSystem::{
    CREATE_NEW, CreateDirectoryW, CreateFileW, FILE_ATTRIBUTE_NORMAL, FILE_ATTRIBUTE_REPARSE_POINT, FILE_FLAG_BACKUP_SEMANTICS,
    FILE_FLAG_OPEN_REPARSE_POINT, FILE_SHARE_NONE, FILE_SHARE_READ, FILE_SHARE_WRITE, GetVolumeInformationByHandleW,
};
use windows::Win32::System::Com::{CoCreateGuid, CoTaskMemFree};
use windows::Win32::System::SystemServices::FILE_PERSISTENT_ACLS;
use windows::Win32::UI::Shell::{FOLDERID_ProgramFiles, KF_FLAG_DEFAULT, SHGetKnownFolderPath};
use windows::core::{PCWSTR, w};

const DLL_BYTES: &[u8] = include_bytes!("../resources/wintun-x64.dll");

/// Owns the extracted DLL and locks its file and directory until all sessions
/// release it. Only SYSTEM and elevated Administrators may access either object.
pub struct WintunDll {
    path: PathBuf,
    file: Option<File>,
    directory: Option<File>,
}

impl WintunDll {
    pub fn prepare() -> anyhow::Result<Self> {
        // Ask Windows for the protected installation root; do not trust TEMP or
        // ProgramFiles environment variables inherited from the launching app.
        let folder = unsafe { SHGetKnownFolderPath(&FOLDERID_ProgramFiles, KF_FLAG_DEFAULT, None)? };
        let root = PathBuf::from(OsString::from_wide(unsafe { folder.as_wide() }));
        unsafe { CoTaskMemFree(Some(folder.0.cast())) };
        let instance = unsafe { CoCreateGuid()? };
        Self::extract_at(root.join(format!("wg-kotlin-wintun-{instance:?}")))
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    fn extract_at(directory: PathBuf) -> anyhow::Result<Self> {
        // An unelevated process belonging to the same account must not own the
        // objects and regain WRITE_DAC through the implicit rights of an owner.
        let mut descriptor = PSECURITY_DESCRIPTOR::default();
        unsafe {
            ConvertStringSecurityDescriptorToSecurityDescriptorW(w!("O:BAG:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)"), 1, &mut descriptor, None)?;
        }
        let attributes = SECURITY_ATTRIBUTES {
            nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: descriptor.0,
            bInheritHandle: false.into(),
        };
        let result = (|| {
            let directory_wide: Vec<u16> = directory.as_os_str().encode_wide().chain(Some(0)).collect();
            // Existing directories, junctions and symlinks are never reused.
            unsafe { CreateDirectoryW(PCWSTR(directory_wide.as_ptr()), Some(&attributes)) }.context("create private Wintun directory")?;
            let mut dll = Self { path: directory.join("wintun.dll"), file: None, directory: None };
            anyhow::ensure!(dll.path.to_str().is_some(), "Wintun DLL path is not Unicode");
            let directory_lock = OpenOptions::new()
                .read(true)
                .share_mode(FILE_SHARE_READ.0 | FILE_SHARE_WRITE.0)
                .custom_flags(FILE_FLAG_BACKUP_SEMANTICS.0 | FILE_FLAG_OPEN_REPARSE_POINT.0)
                .open(&directory)
                .context("lock Wintun directory against replacement")?;
            anyhow::ensure!(
                directory_lock.metadata()?.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT.0 == 0,
                "Wintun directory is a reparse point"
            );
            let mut features = 0;
            unsafe { GetVolumeInformationByHandleW(HANDLE(directory_lock.as_raw_handle()), None, None, None, Some(&mut features), None)? };
            anyhow::ensure!(features & FILE_PERSISTENT_ACLS != 0, "Wintun directory requires filesystem ACL support");
            dll.directory = Some(directory_lock);

            let path_wide: Vec<u16> = dll.path.as_os_str().encode_wide().chain(Some(0)).collect();
            let handle = unsafe {
                CreateFileW(
                    PCWSTR(path_wide.as_ptr()),
                    GENERIC_WRITE.0,
                    FILE_SHARE_NONE,
                    Some(&attributes),
                    CREATE_NEW,
                    FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT,
                    None,
                )
            }
            .context("create Wintun DLL exclusively")?;
            let mut writer = File::from(unsafe { OwnedHandle::from_raw_handle(handle.0) });
            writer.write_all(DLL_BYTES).context("extract embedded Wintun DLL")?;
            writer.sync_all().context("flush Wintun DLL")?;
            drop(writer);
            // The directory and file already have administrator-only ownership
            // and ACLs during this handoff. The loader can read the file, while
            // write/delete opens remain blocked for the rest of its lifetime.
            dll.file = Some(
                OpenOptions::new()
                    .read(true)
                    .share_mode(FILE_SHARE_READ.0)
                    .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT.0)
                    .open(&dll.path)
                    .context("lock Wintun DLL against modification")?,
            );
            Ok(dll)
        })();
        unsafe {
            let _ = LocalFree(HLOCAL(descriptor.0));
        }
        result
    }
}

impl Drop for WintunDll {
    fn drop(&mut self) {
        // Never recursively remove a directory. A forced process termination
        // can leave these protected files behind, but they are never reused.
        drop(self.file.take());
        drop(self.directory.take());
        let _ = std::fs::remove_file(&self.path);
        if let Some(directory) = self.path.parent() {
            let _ = std::fs::remove_dir(directory);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
        assert_eq!(std::fs::read(&path).unwrap(), DLL_BYTES);
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
        use windows::Win32::Security::{
            GetTokenInformation, ImpersonateLoggedOnUser, RevertToSelf, TOKEN_LINKED_TOKEN, TOKEN_QUERY, TokenLinkedToken,
        };
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
}
