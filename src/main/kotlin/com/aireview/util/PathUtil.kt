package com.aireview.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Shared path utilities for resolving and validating file paths.
 *
 * Prevents path traversal attacks by ensuring resolved paths stay within
 * the project base directory.
 */
object PathUtil {

    /**
     * Resolves [relativePath] against [basePath] and validates that the canonical
     * result stays within [basePath]. Returns null if the path escapes the base
     * directory (e.g. via `../../etc/passwd`).
     */
    fun resolveAndValidate(basePath: String, relativePath: String): File? {
        val resolved = File(basePath, relativePath)
        val canonical = resolved.canonicalFile
        val baseCanonical = File(basePath).canonicalFile
        return if (canonical.path.startsWith(baseCanonical.path + File.separator) ||
            canonical.path == baseCanonical.path
        ) {
            canonical
        } else {
            null
        }
    }

    /**
     * Computes the relative path of [vFile] within the [project] base directory.
     * Returns null if the file is not under the project root.
     */
    fun getRelativePath(project: Project, vFile: VirtualFile?): String? {
        if (vFile == null) return null
        val basePath = project.basePath ?: return null
        val filePath = vFile.path
        return if (filePath.startsWith(basePath)) {
            filePath.removePrefix(basePath).removePrefix("/")
        } else null
    }
}
