package com.pushengage.pushengage

import com.google.firebase.FirebaseOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class FcmConfigValidatorTest {

    private val validator = FcmConfigValidator

    private fun options(senderId: String?, projectId: String?): FirebaseOptions {
        val opts = mock<FirebaseOptions>()
        whenever(opts.gcmSenderId).thenReturn(senderId)
        whenever(opts.projectId).thenReturn(projectId)
        return opts
    }

    @Test
    fun match_whenSenderAndProjectAlign() {
        val result = validator.validate(
            serverSenderId = "1234567890",
            serverProjectId = "my-project",
            firebaseOptions = options("1234567890", "my-project")
        )
        assertEquals(FcmConfigValidator.Result.Match, result)
    }

    @Test
    fun senderMismatch_whenSenderDiffersAndProjectMatches() {
        val result = validator.validate(
            serverSenderId = "9999999999",
            serverProjectId = "my-project",
            firebaseOptions = options("1234567890", "my-project")
        )
        assertTrue(result is FcmConfigValidator.Result.SenderMismatch)
        val m = result as FcmConfigValidator.Result.SenderMismatch
        assertEquals("1234567890", m.local)
        assertEquals("9999999999", m.server)
    }

    @Test
    fun projectMismatch_whenProjectDiffersAndSenderMatches() {
        val result = validator.validate(
            serverSenderId = "1234567890",
            serverProjectId = "wrong-project",
            firebaseOptions = options("1234567890", "my-project")
        )
        assertTrue(result is FcmConfigValidator.Result.ProjectMismatch)
        val m = result as FcmConfigValidator.Result.ProjectMismatch
        assertEquals("my-project", m.local)
        assertEquals("wrong-project", m.server)
    }

    @Test
    fun bothMismatch_whenBothSenderAndProjectDiffer() {
        val result = validator.validate(
            serverSenderId = "9999999999",
            serverProjectId = "wrong-project",
            firebaseOptions = options("1234567890", "my-project")
        )
        assertTrue(result is FcmConfigValidator.Result.BothMismatch)
        val m = result as FcmConfigValidator.Result.BothMismatch
        assertEquals("1234567890", m.localSender)
        assertEquals("9999999999", m.serverSender)
        assertEquals("my-project", m.localProject)
        assertEquals("wrong-project", m.serverProject)
    }

    @Test
    fun firebaseUnavailable_whenOptionsNull() {
        val result = validator.validate(
            serverSenderId = "1234567890",
            serverProjectId = "my-project",
            firebaseOptions = null
        )
        assertEquals(FcmConfigValidator.Result.FirebaseUnavailable, result)
    }

    @Test
    fun skipped_whenServerSenderEmptyAndProjectAbsent() {
        val result = validator.validate(
            serverSenderId = "",
            serverProjectId = null,
            firebaseOptions = options("1234567890", "my-project")
        )
        assertEquals(FcmConfigValidator.Result.Skipped, result)
    }

    @Test
    fun skipsSenderComparison_whenServerSenderEmpty_butProjectComparable() {
        // Pre-rollout-like state: only project info comparable.
        val result = validator.validate(
            serverSenderId = "",
            serverProjectId = "my-project",
            firebaseOptions = options("1234567890", "my-project")
        )
        assertEquals(FcmConfigValidator.Result.Match, result)
    }

    @Test
    fun skipsProjectComparison_whenServerProjectAbsent_butSenderMatches() {
        // Backend rollout safety: firebase_project_id field not yet shipped.
        val result = validator.validate(
            serverSenderId = "1234567890",
            serverProjectId = null,
            firebaseOptions = options("1234567890", "my-project")
        )
        assertEquals(FcmConfigValidator.Result.Match, result)
    }

    @Test
    fun skipsProjectComparison_whenLocalProjectEmpty() {
        val result = validator.validate(
            serverSenderId = "1234567890",
            serverProjectId = "my-project",
            firebaseOptions = options("1234567890", null)
        )
        assertEquals(FcmConfigValidator.Result.Match, result)
    }

    @Test
    fun caseInsensitiveProjectIdComparison() {
        val result = validator.validate(
            serverSenderId = "1234567890",
            serverProjectId = "My-Project",
            firebaseOptions = options("1234567890", "my-project")
        )
        assertEquals(FcmConfigValidator.Result.Match, result)
    }

    @Test
    fun whitespaceTrimmedBeforeComparison() {
        val result = validator.validate(
            serverSenderId = "  1234567890  ",
            serverProjectId = " my-project ",
            firebaseOptions = options("1234567890", "my-project")
        )
        assertEquals(FcmConfigValidator.Result.Match, result)
    }
}
