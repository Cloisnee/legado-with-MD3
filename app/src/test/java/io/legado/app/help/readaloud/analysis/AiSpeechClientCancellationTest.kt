package io.legado.app.help.readaloud.analysis

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.repository.AiModelEntry
import io.legado.app.data.repository.AiModelRepository
import io.legado.app.data.repository.AiProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AiSpeechClientCancellationTest {
    @Test fun `cancel waiting for response headers interrupts real call and returns permit`() = checkCancellation(false)
    @Test fun `cancel reading slow sse body interrupts real call and returns permit`() = checkCancellation(true)

    private fun checkCancellation(sendSse: Boolean) = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val received = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val thread = Thread({
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val source = socket.getInputStream()
                    val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) {
                        val byte = source.read()
                        check(byte >= 0)
                        headers.append(byte.toChar())
                    }
                    val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                        .find(headers)?.groupValues?.get(1)?.toInt() ?: 0
                    repeat(length) { check(source.read() >= 0) }
                    if (sendSse) {
                        val frame = "data: {\"choices\":[{\"delta\":{\"content\":\"part\"}}]}\n\n"
                        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                            "Transfer-Encoding: chunked\r\n\r\n${frame.toByteArray().size.toString(16)}\r\n$frame\r\n"
                        socket.getOutputStream().write(response.toByteArray())
                        socket.getOutputStream().flush()
                    }
                    received.countDown()
                    if (source.read() == -1) closed.countDown()
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }, "slow-ai-fixture").apply { isDaemon = true; start() }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val scheduler = AiRequestScheduler()
        val provider = AiProvider("local", "local", "http://127.0.0.1:${server.localPort}", "fixture-key")
        val model = AiModelEntry("m", provider.id, "m", "m", requestAttempts = 1)
        val ref = AiSpeechClient.ModelRef(model, provider)
        val client = AiSpeechClient(AiModelRepository(app), AnalysisConfigStore(app), scheduler)
        val context = AiSchedulerContext(AiTaskKind.STAGE2, AiPriority.P3_BACKGROUND, "bk", listOf(0), true)
        val job = launch(Dispatchers.IO) {
            client.completeValidated(listOf(ref), "", { "fixture" }, logTag = "cancel-test", context = context) {
                ValidateOutcome(it)
            }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { received.await(3, TimeUnit.SECONDS) })
            job.cancel()
            withTimeout(1_000) { job.join() }
            assertTrue(withContext(Dispatchers.IO) { closed.await(1, TimeUnit.SECONDS) })
            withTimeout(1_000) { scheduler.withPermit(ref, context.copy(priority = AiPriority.P0_CURRENT)) { } }
            assertNull(failure.get())
        } finally {
            job.cancelAndJoin()
            server.close()
            withContext(Dispatchers.IO) { thread.join(1_000) }
        }
    }
}
