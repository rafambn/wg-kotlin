package com.rafambn.kmpvpn.daemon.protocol

import kotlinx.serialization.Serializable

@Serializable
sealed class CommandResult<out S> {
    @Serializable
    data class Success<out S>(
        val data: S,
    ) : CommandResult<S>() {
        override fun toString(): String {
            return "Success(data=$data)"
        }
    }

    @Serializable
    data class Failure(
        val kind: DaemonErrorKind,
        val message: String,
        val detail: DaemonFailureDetail? = null,
    ) : CommandResult<Nothing>() {
        override fun toString(): String {
            return "Failure(kind=$kind, message=$message, detail=$detail)"
        }
    }

    val isSuccess: Boolean
        get() = this is Success<*>

    val isFailure: Boolean
        get() = this is Failure

    inline fun onSuccess(action: (Success<S>) -> Unit): CommandResult<S> {
        if (this is Success<S>) {
            action(this)
        }
        return this
    }

    inline fun onFailure(action: (Failure) -> Unit): CommandResult<S> {
        if (this is Failure) {
            action(this)
        }
        return this
    }

    companion object {
        fun <S> success(data: S): CommandResult<S> {
            return Success(data = data)
        }

        fun <S> failure(
            kind: DaemonErrorKind,
            message: String,
            detail: DaemonFailureDetail? = null,
        ): CommandResult<S> {
            return Failure(
                kind = kind,
                message = message,
                detail = detail,
            )
        }
    }
}
