package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.domain.model.readaloud.v4.RaIds

/**
 * 换源/本地书重绑定的同步身份迁移：直接写 ra_book_identity，避免异步任务与 keyOf 竞态。
 * 必须在 bookUrl 正式切换的同一事务内或紧邻事务前调用。
 */
object RaBookIdentitySync {

    fun migrate(db: AppDatabase, oldBookUrl: String, newBookUrl: String, newSourceKey: String) {
        if (oldBookUrl.isBlank() || newBookUrl.isBlank() || oldBookUrl == newBookUrl) return
        val sql = db.openHelper.writableDatabase
        var key: String? = null
        var createdAt = System.currentTimeMillis()
        sql.query(
            "select bookKey, createdAt from ra_book_identity where bookUrl = ? limit 1",
            arrayOf(oldBookUrl),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                key = cursor.getString(0)
                createdAt = cursor.getLong(1)
            }
        }
        val existingKey = key
        if (existingKey == null) {
            // 旧 URL 没有身份记录：新 URL 已有记录就不覆盖；否则新建
            var hasNew = false
            sql.query(
                "select bookKey from ra_book_identity where bookUrl = ? limit 1",
                arrayOf(newBookUrl),
            ).use { cursor -> hasNew = cursor.moveToFirst() }
            if (hasNew) return
        }
        val bookKey = existingKey ?: RaIds.book()
        sql.execSQL(
            "insert or replace into ra_book_identity" +
                "(bookUrl, bookKey, sourceKey, createdAt, updatedAt) values(?,?,?,?,?)",
            arrayOf<Any?>(newBookUrl, bookKey, newSourceKey, createdAt, System.currentTimeMillis()),
        )
    }
}
