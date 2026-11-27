package com.music.laya.desktop

/** Which desktop this is running on. */
internal object DesktopPlatform {

    private val name: String = System.getProperty("os.name").orEmpty()

    val isWindows: Boolean = name.startsWith("Windows", ignoreCase = true)

    val isLinux: Boolean = name.contains("linux", ignoreCase = true)

    val isMac: Boolean = name.contains("mac", ignoreCase = true)

    /**
     * 7 (also 8 / 8.1), 10 or 11: which Windows draws the caption buttons Laya copies. Windows 11 still reports
     * version 10.0, so the build number decides (22000 and later are 11), read from the registry; the JDK's own
     * name ("Windows 11") stands in where that can't be read. 0 off Windows.
     */
    val windowsGeneration: Int by lazy {
        if (!isWindows) return@lazy 0
        val build = runCatching {
            com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(
                com.sun.jna.platform.win32.WinReg.HKEY_LOCAL_MACHINE,
                "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion",
                "CurrentBuildNumber",
            ).trim().toInt()
        }.getOrNull()
        val major = System.getProperty("os.version").orEmpty().substringBefore('.').toIntOrNull()
        when {
            build != null && build >= 22000 -> 11
            build != null && build >= 10240 -> 10
            build != null -> 7
            name.contains("11") -> 11
            major != null && major >= 10 -> 10
            else -> 7
        }
    }

    /**
     * Whose caption buttons the window draws: [windowsGeneration] on Windows, 0 (the platform's own) elsewhere.
     * `LAYA_WINDOWS_CAPTION` (or `-Dlaya.windowsCaption`) set to 7, 10 or 11 draws that Windows' buttons on any
     * desktop that draws its own frame, to try them without a Windows machine. Only the buttons change: the native
     * frame, maximize and the rest still go by the real platform.
     */
    val windowsCaption: Int by lazy {
        val forced = (System.getProperty("laya.windowsCaption") ?: System.getenv("LAYA_WINDOWS_CAPTION"))
            ?.trim()?.toIntOrNull()?.takeIf { it == 7 || it == 10 || it == 11 }
        forced ?: windowsGeneration
    }

    /** Whether the application draws the window's own frame instead of the system drawing it. */
    val drawsOwnWindowFrame: Boolean = isWindows || isMac
}
