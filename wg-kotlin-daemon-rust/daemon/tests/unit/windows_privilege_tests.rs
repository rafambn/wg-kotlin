use super::OwnerProcess;

// Freeze the original privilege check for comparison on native Windows.
fn previous_ensure_root() -> anyhow::Result<()> {
    use windows::Win32::Foundation::{CloseHandle, HANDLE};
    use windows::Win32::Security::{GetTokenInformation, TOKEN_ELEVATION, TOKEN_QUERY};
    use windows::Win32::System::Threading::{GetCurrentProcess, OpenProcessToken};

    unsafe {
        let mut token = HANDLE::default();
        OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token)?;

        let mut elevation = TOKEN_ELEVATION::default();
        let mut return_length = 0u32;

        GetTokenInformation(
            token,
            windows::Win32::Security::TokenElevation,
            Some(&mut elevation as *mut _ as *mut _),
            std::mem::size_of::<TOKEN_ELEVATION>() as u32,
            &mut return_length,
        )?;

        CloseHandle(token)?;

        anyhow::ensure!(elevation.TokenIsElevated != 0, "wg-kotlin-daemon requires Administrator privileges on Windows");
        Ok(())
    }
}

#[test]
fn elevation_decision_and_diagnostics_match_the_previous_check() {
    // PID zero stops preparation before opening a process or changing credentials.
    let current = OwnerProcess::prepare(0, 0).err().unwrap().to_string();
    match previous_ensure_root() {
        Ok(()) => assert_eq!(current, "invalid owner PID"),
        Err(error) => assert_eq!(current, error.to_string()),
    }
}
