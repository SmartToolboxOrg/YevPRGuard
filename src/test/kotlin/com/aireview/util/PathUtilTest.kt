package com.aireview.util

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PathUtilTest {

    private val tempDir = System.getProperty("java.io.tmpdir")

    @Test
    fun `resolveAndValidate returns file for valid relative path`() {
        val result = PathUtil.resolveAndValidate(tempDir, "test.txt")
        assertNotNull(result)
        assertTrue(result!!.path.startsWith(File(tempDir).canonicalPath))
    }

    @Test
    fun `resolveAndValidate blocks path traversal with dot-dot`() {
        val result = PathUtil.resolveAndValidate(tempDir, "../../etc/passwd")
        // Either null (if /etc/passwd is outside tempDir) or still valid
        if (result != null) {
            assertTrue(result.path.startsWith(File(tempDir).canonicalPath))
        }
    }

    @Test
    fun `resolveAndValidate blocks absolute path escape`() {
        val base = File(tempDir, "sandbox").also { it.mkdirs() }
        val result = PathUtil.resolveAndValidate(base.absolutePath, "../../../etc/passwd")
        assertNull(result)
    }

    @Test
    fun `resolveAndValidate allows nested paths`() {
        val result = PathUtil.resolveAndValidate(tempDir, "sub/dir/file.kt")
        assertNotNull(result)
        assertTrue(result!!.path.contains("sub"))
    }

    @Test
    fun `resolveAndValidate returns base dir itself`() {
        val result = PathUtil.resolveAndValidate(tempDir, ".")
        assertNotNull(result)
    }

    @Test
    fun `resolveAndValidate handles empty relative path`() {
        val result = PathUtil.resolveAndValidate(tempDir, "")
        assertNotNull(result)
    }
}
