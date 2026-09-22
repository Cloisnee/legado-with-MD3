package io.legado.app.domain.model.settings

data class BackupSettings(
    val webDavUrl: String = "",
    val webDavAccount: String = "",
    val webDavPassword: String = "",
    val webDavDir: String = "legado",
    val webDavDeviceName: String = "",
    val syncBookProgress: Boolean = true,
    val syncBookProgressPlus: Boolean = false,
    val autoCheckNewBackup: Boolean = false,
    val onlyLatestBackup: Boolean = false,
    val backupSyncMode: String = "both",
    val backupPath: String? = null,
)
