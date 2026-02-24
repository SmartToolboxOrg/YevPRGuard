package com.aireview.service

import org.junit.Assert.*
import org.junit.Test

class GitDiffServiceTest {

    @Test
    fun `detectLanguage maps kotlin extensions`() {
        assertEquals("KOTLIN", GitDiffService.detectLanguage("src/main/Foo.kt"))
        assertEquals("KOTLIN", GitDiffService.detectLanguage("build.gradle.kts"))
    }

    @Test
    fun `detectLanguage maps java extension`() {
        assertEquals("JAVA", GitDiffService.detectLanguage("Main.java"))
    }

    @Test
    fun `detectLanguage maps typescript extensions`() {
        assertEquals("TYPESCRIPT", GitDiffService.detectLanguage("app.ts"))
        assertEquals("TYPESCRIPT", GitDiffService.detectLanguage("Component.tsx"))
    }

    @Test
    fun `detectLanguage maps javascript extensions`() {
        assertEquals("JAVASCRIPT", GitDiffService.detectLanguage("index.js"))
        assertEquals("JAVASCRIPT", GitDiffService.detectLanguage("App.jsx"))
    }

    @Test
    fun `detectLanguage maps python`() {
        assertEquals("PYTHON", GitDiffService.detectLanguage("script.py"))
    }

    @Test
    fun `detectLanguage maps go`() {
        assertEquals("GO", GitDiffService.detectLanguage("main.go"))
    }

    @Test
    fun `detectLanguage maps rust`() {
        assertEquals("RUST", GitDiffService.detectLanguage("lib.rs"))
    }

    @Test
    fun `detectLanguage maps ruby`() {
        assertEquals("RUBY", GitDiffService.detectLanguage("app.rb"))
    }

    @Test
    fun `detectLanguage maps c-cpp variants`() {
        assertEquals("C_CPP", GitDiffService.detectLanguage("main.c"))
        assertEquals("C_CPP", GitDiffService.detectLanguage("main.cpp"))
        assertEquals("C_CPP", GitDiffService.detectLanguage("main.cc"))
        assertEquals("C_CPP", GitDiffService.detectLanguage("main.cxx"))
    }

    @Test
    fun `detectLanguage maps csharp`() {
        assertEquals("CSHARP", GitDiffService.detectLanguage("Program.cs"))
    }

    @Test
    fun `detectLanguage maps config formats`() {
        assertEquals("XML", GitDiffService.detectLanguage("pom.xml"))
        assertEquals("YAML", GitDiffService.detectLanguage("config.yaml"))
        assertEquals("YAML", GitDiffService.detectLanguage("config.yml"))
        assertEquals("JSON", GitDiffService.detectLanguage("package.json"))
        assertEquals("SQL", GitDiffService.detectLanguage("schema.sql"))
        assertEquals("SHELL", GitDiffService.detectLanguage("deploy.sh"))
        assertEquals("SHELL", GitDiffService.detectLanguage("init.bash"))
        assertEquals("MARKDOWN", GitDiffService.detectLanguage("README.md"))
    }

    @Test
    fun `detectLanguage returns null for unknown extension`() {
        assertNull(GitDiffService.detectLanguage("file.xyz"))
        assertNull(GitDiffService.detectLanguage("Makefile"))
    }

    @Test
    fun `detectLanguage is case insensitive`() {
        assertEquals("JAVA", GitDiffService.detectLanguage("Main.JAVA"))
        assertEquals("KOTLIN", GitDiffService.detectLanguage("Main.KT"))
    }

    @Test
    fun `detectLanguage handles nested paths`() {
        assertEquals("KOTLIN", GitDiffService.detectLanguage("src/main/kotlin/com/example/Foo.kt"))
    }

    @Test
    fun `detectLanguage handles file with multiple dots`() {
        assertEquals("KOTLIN", GitDiffService.detectLanguage("my.module.service.kt"))
    }

    @Test
    fun `detectLanguage handles scala`() {
        assertEquals("SCALA", GitDiffService.detectLanguage("Main.scala"))
    }

    @Test
    fun `detectLanguage handles swift`() {
        assertEquals("SWIFT", GitDiffService.detectLanguage("App.swift"))
    }
}
