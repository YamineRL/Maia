package dev.maia.actions.notes

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The two pure pieces of [SafNotes], which are the only pieces of it a JVM can
 * reach: what Maia does next when the provider throws, and how a folder or file
 * name is read off a document id.
 *
 * Everything asserted here is a transcription of something that happened on the
 * Pixel on 2026-09-13 (`spike/saf-notes/README.md`, gates G8 and G9) or was
 * read out of AOSP (`docs/research/M4-R1.md`). The rest of [SafNotes] needs a
 * `DocumentsProvider`, so it needs a phone, and no test in this module says
 * anything about it.
 */
class SafFaultsTest {

    // ------------------------------------------------------ the grant is gone

    @Test
    fun `a security exception is the folder going away, from either call`() {
        // G9(a): release the grant and append, and the provider answers
        // "Permission Denial: opening provider ...ExternalStorageProvider".
        val denied = SecurityException("Permission Denial: opening provider")
        assertEquals(SafFault.FOLDER_GONE, SafFaults.after(denied, cachedChild = true))
        assertEquals(SafFault.FOLDER_GONE, SafFaults.after(denied, cachedChild = false))
    }

    @Test
    fun `a missing file on the tree is the folder going away`() {
        // G9(b): with the folder deleted, the children query throws
        // "Missing file for primary:Documents/... at /storage/emulated/0/...",
        // and the grant is still listed with read and write, so the grant list
        // cannot be what decides this.
        val missing = FileNotFoundException("Missing file for primary:Documents/notes")
        assertEquals(SafFault.FOLDER_GONE, SafFaults.after(missing, cachedChild = false))
    }

    // -------------------------------------------------- the cached child went

    @Test
    fun `a missing file behind a cached child is re-resolved, not reported`() {
        val missing = FileNotFoundException("Missing file for primary:Documents/notes/2026-09-13.md")
        assertEquals(SafFault.RESOLVE_AGAIN, SafFaults.after(missing, cachedChild = true))
    }

    @Test
    fun `G8's illegal argument exception wrapping a missing file is re-resolved`() {
        // The exact reading, and the reason this class exists. The provider's
        // child check builds its message by concatenating the exception, so the
        // FileNotFoundException is in the TEXT and not in the cause chain, and
        // code that caught FileNotFoundException alone would have called this a
        // fault and shown the user a stack trace for a file a sync client had
        // just replaced.
        val asSeen = IllegalArgumentException(
            "Failed to determine if primary:Documents/notes/2026-09-13.md is child of " +
                "primary:Documents/notes: java.io.FileNotFoundException",
        )
        assertEquals(SafFault.RESOLVE_AGAIN, SafFaults.after(asSeen, cachedChild = true))

        // And the same call with a real cause, in case a build wraps it
        // properly rather than by string.
        val wrapped = IllegalArgumentException("Failed to determine", FileNotFoundException("Missing file"))
        assertEquals(SafFault.RESOLVE_AGAIN, SafFaults.after(wrapped, cachedChild = true))
    }

    @Test
    fun `an illegal argument exception about anything else is a fault`() {
        // Never "re-resolve" by default: an unknown URI is not a stale child,
        // and retrying the whole resolve on it would hide a wiring mistake.
        val other = IllegalArgumentException("Unknown URI content://com.example/1")
        assertEquals(SafFault.WRITE_FAILED, SafFaults.after(other, cachedChild = true))
        assertEquals(SafFault.WRITE_FAILED, SafFaults.after(other, cachedChild = false))
    }

    @Test
    fun `an ordinary write failure is a fault and not a missing folder`() {
        assertEquals(SafFault.WRITE_FAILED, SafFaults.after(IOException("write failed"), cachedChild = false))
        assertEquals(SafFault.WRITE_FAILED, SafFaults.after(IllegalStateException("closed"), cachedChild = true))
    }

    // ------------------------------------------------- before the first unlock

    @Test
    fun `nothing is the folder going away before the first unlock`() {
        // M4-R1: ExternalStorageProvider is not direct boot aware, so between a
        // reboot and the first unlock the grant is listed and the write fails.
        // Reading that as a revoked grant would march the user into the folder
        // picker to re-choose a folder that was never lost.
        val unlocked = false
        assertEquals(
            SafFault.WRITE_FAILED,
            SafFaults.after(SecurityException("denied"), cachedChild = false, userUnlocked = unlocked),
        )
        assertEquals(
            SafFault.WRITE_FAILED,
            SafFaults.after(FileNotFoundException("Missing file"), cachedChild = false, userUnlocked = unlocked),
        )
        assertEquals(
            SafFault.WRITE_FAILED,
            SafFaults.after(FileNotFoundException("Missing file"), cachedChild = true, userUnlocked = unlocked),
        )
    }

    // ------------------------------------------------------------- the naming

    @Test
    fun `a folder name is the last segment of a path-based document id`() {
        // ExternalStorageProvider ids are <rootId>:<path from the root>
        // (M4-R6), and the card is allowed to say a folder name and never a
        // path (privacy item V2).
        assertEquals("maia-saf-spike", nameFromDocumentId("primary:Documents/maia-saf-spike"))
        assertEquals("Notes", nameFromDocumentId("primary:Notes"))
        assertEquals("2026-09-13.md", nameFromDocumentId("primary:Documents/Notes/2026-09-13.md"))
        assertEquals("2026-09-13 (1).md", nameFromDocumentId("primary:Documents/Notes/2026-09-13 (1).md"))
    }

    @Test
    fun `the root of a volume still has a name, and an opaque id survives`() {
        assertEquals("primary", nameFromDocumentId("primary:"))
        // A provider with opaque ids (G11, never run) gives something
        // unreadable rather than something wrong.
        assertEquals("7a1f9c", nameFromDocumentId("7a1f9c"))
    }
}
