package io.legado.app.data.repository

import io.legado.app.data.dao.readaloud.RaBuildDao
import io.legado.app.domain.gateway.RaBookIdentityGateway
import io.legado.app.domain.gateway.RaBookIdentityRow
import io.legado.app.domain.model.readaloud.v4.RaIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RaBookIdentityRepository(
    private val dao: RaBuildDao,
) : RaBookIdentityGateway {

    private val mutex = Mutex()

    override suspend fun keyOf(bookUrl: String, sourceKey: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = dao.getIdentity(bookUrl)
            if (existing != null) {
                if (existing.sourceKey != sourceKey) {
                    dao.upsertIdentity(
                        existing.copy(sourceKey = sourceKey, updatedAt = System.currentTimeMillis()),
                    )
                }
                return@withLock existing.bookKey
            }
            val key = RaIds.book()
            dao.upsertIdentity(
                io.legado.app.data.entities.readaloud.RaBookIdentityEntity(
                    bookUrl = bookUrl,
                    bookKey = key,
                    sourceKey = sourceKey,
                ),
            )
            key
        }
    }

    override suspend fun bookKeyOf(bookUrl: String): String? = withContext(Dispatchers.IO) {
        dao.getIdentity(bookUrl)?.bookKey
    }

    override suspend fun sourceKeyOf(bookUrl: String): String? = withContext(Dispatchers.IO) {
        dao.getIdentity(bookUrl)?.sourceKey
    }

    override suspend fun onSourceChanged(
        oldBookUrl: String,
        newBookUrl: String,
        newSourceKey: String,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (oldBookUrl.isBlank() || newBookUrl.isBlank() || oldBookUrl == newBookUrl) return@withLock
            val old = dao.getIdentity(oldBookUrl)
            if (old != null) {
                dao.upsertIdentity(
                    io.legado.app.data.entities.readaloud.RaBookIdentityEntity(
                        bookUrl = newBookUrl,
                        bookKey = old.bookKey,
                        sourceKey = newSourceKey,
                        createdAt = old.createdAt,
                    ),
                )
            } else {
                val existingNew = dao.getIdentity(newBookUrl)
                if (existingNew == null) {
                    dao.upsertIdentity(
                        io.legado.app.data.entities.readaloud.RaBookIdentityEntity(
                            bookUrl = newBookUrl,
                            bookKey = RaIds.book(),
                            sourceKey = newSourceKey,
                        ),
                    )
                }
            }
        }
    }

    override suspend fun identitiesOf(bookKey: String): List<RaBookIdentityRow> =
        withContext(Dispatchers.IO) {
            dao.identitiesOf(bookKey).map {
                RaBookIdentityRow(it.bookUrl, it.bookKey, it.sourceKey)
            }
        }

    override suspend fun deleteBookIdentity(bookUrl: String) = withContext(Dispatchers.IO) {
        dao.deleteIdentity(bookUrl)
    }
}
