package com.voidedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SftpManagerPathTest {
    @Test
    fun renameFileRemapsActiveEditorPath() {
        assertEquals(
            "/srv/new.txt",
            SftpManager.remapPathAfterRename("/srv/old.txt", "/srv/old.txt", "/srv/new.txt")
        )
    }

    @Test
    fun renameParentFolderRemapsDescendantEditorPath() {
        assertEquals(
            "/srv/new/nested/file.txt",
            SftpManager.remapPathAfterRename(
                "/srv/old/nested/file.txt",
                "/srv/old",
                "/srv/new"
            )
        )
    }

    @Test
    fun unrelatedRenameLeavesEditorPathAlone() {
        assertEquals(
            "/srv/project/file.txt",
            SftpManager.remapPathAfterRename(
                "/srv/project/file.txt",
                "/srv/other",
                "/srv/renamed"
            )
        )
    }

    @Test
    fun deleteDetectsExactPathAndDescendantsOnly() {
        assertTrue(SftpManager.containsPath("/srv/project", "/srv/project"))
        assertTrue(SftpManager.containsPath("/srv/project", "/srv/project/src/file.kt"))
        assertFalse(SftpManager.containsPath("/srv/project", "/srv/project-old/file.kt"))
    }

    @Test
    fun duplicateSanitizedZipPathsReceiveStableSuffixes() {
        val used = mutableSetOf<String>()
        assertEquals("assets/a_b.js", SftpManager.uniqueZipPath("assets/a_b.js", used))
        assertEquals("assets/a_b (2).js", SftpManager.uniqueZipPath("assets/a_b.js", used))
        assertEquals("assets/a_b (3).js", SftpManager.uniqueZipPath("assets/a_b.js", used))
    }
}
