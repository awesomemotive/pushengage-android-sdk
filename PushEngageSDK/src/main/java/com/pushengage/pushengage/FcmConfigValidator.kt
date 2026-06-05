package com.pushengage.pushengage

import com.google.firebase.FirebaseOptions

internal object FcmConfigValidator {

    sealed class Result {
        object Match : Result()
        object Skipped : Result()
        object FirebaseUnavailable : Result()
        data class SenderMismatch(val local: String, val server: String) : Result()
        data class ProjectMismatch(val local: String, val server: String) : Result()
        data class BothMismatch(
            val localSender: String,
            val serverSender: String,
            val localProject: String,
            val serverProject: String
        ) : Result()
    }

    @JvmStatic
    fun validate(
        serverSenderId: String?,
        serverProjectId: String?,
        firebaseOptions: FirebaseOptions?
    ): Result {
        if (firebaseOptions == null) return Result.FirebaseUnavailable

        val localSender = firebaseOptions.gcmSenderId?.trim().orEmpty()
        val localProject = firebaseOptions.projectId?.trim().orEmpty()
        val serverSender = serverSenderId?.trim().orEmpty()
        val serverProject = serverProjectId?.trim().orEmpty()

        val senderComparable = serverSender.isNotEmpty() && localSender.isNotEmpty()
        val projectComparable = serverProject.isNotEmpty() && localProject.isNotEmpty()

        if (!senderComparable && !projectComparable) return Result.Skipped

        val senderMismatch = senderComparable && !localSender.equals(serverSender, ignoreCase = true)
        val projectMismatch = projectComparable && !localProject.equals(serverProject, ignoreCase = true)

        return when {
            senderMismatch && projectMismatch -> Result.BothMismatch(
                localSender = localSender,
                serverSender = serverSender,
                localProject = localProject,
                serverProject = serverProject
            )
            senderMismatch -> Result.SenderMismatch(local = localSender, server = serverSender)
            projectMismatch -> Result.ProjectMismatch(local = localProject, server = serverProject)
            else -> Result.Match
        }
    }
}
