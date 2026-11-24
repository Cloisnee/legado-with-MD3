package io.legado.app.help.readaloud.analysis.v4

/** 泛称判定（裸属词/单字），用于身份链接；V4-1 由词库实现，测试可注入假实现。 */
interface GenericNameClassifier {
    suspend fun isGeneric(name: String): Boolean
}
