package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "read_aloud_voices",
    indices = [
        Index(value = ["engineType", "engineId", "speakerId"], unique = true),
        Index(value = ["enabled", "displayName"]),
    ],
    primaryKeys = ["id"],
)
data class ReadAloudVoiceEntity(
    val id: String,
    val engineType: String,
    @ColumnInfo(defaultValue = "")
    val engineId: String = "",
    @ColumnInfo(defaultValue = "")
    val speakerId: String = "",
    val displayName: String,
    @ColumnInfo(defaultValue = "[]")
    val traitsJson: String = "[]",
    @ColumnInfo(defaultValue = "[]")
    val emotionCatalogJson: String = "[]",
    @ColumnInfo(defaultValue = "user")
    val managedBy: String = "user",
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "1")
    val available: Boolean = true,
    /**
     * 用户主动排除：源自带的目录里不想用的那些音色。
     *
     * 不是删除——行必须留着，否则下次导入同一个源，[io.legado.app.domain.usecase
     * .SyncReadAloudVoicesUseCase] 会把它当成新音色重新插回来。
     */
    @ColumnInfo(defaultValue = "0")
    val excluded: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val revision: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
