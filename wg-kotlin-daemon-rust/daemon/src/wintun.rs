use anyhow::Context;
use std::ffi::OsString;
use std::fs::{File, OpenOptions};
use std::io::Write;
use std::os::windows::ffi::OsStringExt;
use std::os::windows::fs::{MetadataExt, OpenOptionsExt};
use std::os::windows::io::{AsRawHandle, FromRawHandle};
use std::path::{Path, PathBuf};
use windows::Win32::Foundation::{GENERIC_WRITE, HANDLE, HLOCAL};
use windows::Win32::Security::Authorization::ConvertStringSecurityDescriptorToSecurityDescriptorW;
use windows::Win32::Security::{PSECURITY_DESCRIPTOR, SECURITY_ATTRIBUTES};
use windows::Win32::Storage::FileSystem::{
    CREATE_NEW, CreateDirectoryW, CreateFileW, FILE_ATTRIBUTE_NORMAL, FILE_ATTRIBUTE_REPARSE_POINT, FILE_FLAG_BACKUP_SEMANTICS,
    FILE_FLAG_OPEN_REPARSE_POINT, FILE_SHARE_NONE, FILE_SHARE_READ, FILE_SHARE_WRITE, GetVolumeInformationByHandleW,
};
use windows::Win32::System::Com::{CoCreateGuid, CoTaskMemFree};
use windows::Win32::System::SystemServices::FILE_PERSISTENT_ACLS;
use windows::Win32::UI::Shell::{FOLDERID_ProgramFiles, KF_FLAG_DEFAULT, SHGetKnownFolderPath};
use windows::core::{HSTRING, w};

/// Owns the extracted DLL and locks its file and directory until all sessions
/// release it. Only SYSTEM and elevated Administrators may access either object.
pub struct WintunDll {
    // Fields drop in this order: release both locks before removing their paths.
    _locks: [File; 2],
    files: DllFiles,
}

struct DllFiles(PathBuf);

impl WintunDll {
    pub fn prepare() -> anyhow::Result<Self> {
        // Ask Windows for the protected installation root; do not trust TEMP or
        // ProgramFiles environment variables inherited from the launching app.
        let folder = unsafe { SHGetKnownFolderPath(&FOLDERID_ProgramFiles, KF_FLAG_DEFAULT, None)? };
        let root = PathBuf::from(OsString::from_wide(unsafe { folder.as_wide() }));
        unsafe { CoTaskMemFree(Some(folder.0.cast())) };
        Self::extract_at(root.join(format!("wg-kotlin-wintun-{:?}", unsafe { CoCreateGuid()? })))
    }

    pub fn path(&self) -> &Path {
        &self.files.0
    }

    fn extract_at(directory: PathBuf) -> anyhow::Result<Self> {
        // An unelevated process belonging to the same account must not own the
        // objects and regain WRITE_DAC through the implicit rights of an owner.
        let mut descriptor = PSECURITY_DESCRIPTOR::default();
        unsafe { ConvertStringSecurityDescriptorToSecurityDescriptorW(w!("O:BAG:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)"), 1, &mut descriptor, None) }?;
        let _descriptor = unsafe { windows::core::Owned::new(HLOCAL(descriptor.0)) };
        let attributes = SECURITY_ATTRIBUTES {
            nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: descriptor.0,
            bInheritHandle: false.into(),
        };
        // Existing directories, junctions and symlinks are never reused.
        unsafe { CreateDirectoryW(&HSTRING::from(directory.as_path()), Some(&attributes)) }.context("create private Wintun directory")?;
        let files = DllFiles(directory.join("wintun.dll"));
        anyhow::ensure!(files.0.to_str().is_some(), "Wintun DLL path is not Unicode");
        let directory_lock = OpenOptions::new()
            .read(true)
            .share_mode(FILE_SHARE_READ.0 | FILE_SHARE_WRITE.0)
            .custom_flags(FILE_FLAG_BACKUP_SEMANTICS.0 | FILE_FLAG_OPEN_REPARSE_POINT.0)
            .open(&directory)
            .context("lock Wintun directory against replacement")?;
        anyhow::ensure!(directory_lock.metadata()?.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT.0 == 0, "Wintun directory is a reparse point");
        let mut features = 0;
        unsafe { GetVolumeInformationByHandleW(HANDLE(directory_lock.as_raw_handle()), None, None, None, Some(&mut features), None)? };
        anyhow::ensure!(features & FILE_PERSISTENT_ACLS != 0, "Wintun directory requires filesystem ACL support");

        let handle = unsafe {
            CreateFileW(
                &HSTRING::from(files.0.as_path()),
                GENERIC_WRITE.0,
                FILE_SHARE_NONE,
                Some(&attributes),
                CREATE_NEW,
                FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT,
                None,
            )
        }
        .context("create Wintun DLL exclusively")?;
        let mut writer = unsafe { File::from_raw_handle(handle.0) };
        writer.write_all(include_bytes!("../resources/wintun-x64.dll")).context("extract embedded Wintun DLL")?;
        writer.sync_all().context("flush Wintun DLL")?;
        drop(writer);
        // The directory and file already have administrator-only ownership
        // and ACLs during this handoff. The loader can read the file, while
        // write/delete opens remain blocked for the rest of its lifetime.
        let file_lock = OpenOptions::new()
            .read(true)
            .share_mode(FILE_SHARE_READ.0)
            .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT.0)
            .open(&files.0)
            .context("lock Wintun DLL against modification")?;
        Ok(Self { _locks: [file_lock, directory_lock], files })
    }
}

impl Drop for DllFiles {
    fn drop(&mut self) {
        // Never recursively remove a directory. A forced process termination
        // can leave these protected files behind, but they are never reused.
        let _ = std::fs::remove_file(&self.0);
        let _ = self.0.parent().map(std::fs::remove_dir);
    }
}

#[cfg(test)]
#[path = "../tests/unit/wintun_tests.rs"]
mod tests;
