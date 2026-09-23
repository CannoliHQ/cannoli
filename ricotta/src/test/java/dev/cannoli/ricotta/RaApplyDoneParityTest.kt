package dev.cannoli.ricotta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RaApplyDoneParityTest {
    private val bridge = File("jni/ricotta_bridge.c").readText()
    private val kotlin = File("src/main/java/dev/cannoli/ricotta/EmbeddedRetroArchBridge.kt").readText()

    @Test fun `the upcall C looks up is the one Kotlin declares`() {
        assertTrue(Regex("\"onRaApplyDone\",\\s*\"\\(ILjava/lang/String;\\[Ljava/lang/String;\\)V\"").containsMatchIn(bridge))
        assertTrue(kotlin.contains("fun onRaApplyDone(token: Int, value: String?, moved: Array<String>)"))
    }

    @Test fun `the native takes a token and a watch list and answers whether it queued`() {
        assertTrue(kotlin.contains("private external fun nativeRaApply(token: Int, key: String, value: String, watch: Array<String>): Boolean"))
        assertTrue(bridge.contains("JNIEnv *env, jobject obj, jint token, jstring jkey, jstring jvalue, jobjectArray jwatch)"))
    }

    @Test fun `nothing waits on a condition variable any more`() {
        assertFalse(bridge.contains("g_apply_cond"))
        assertFalse(kotlin.contains("APPLY_TIMEOUT_MS"))
    }
}
