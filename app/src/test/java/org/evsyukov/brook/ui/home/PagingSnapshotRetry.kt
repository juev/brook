package org.evsyukov.brook.ui.home

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * A paging snapshot races a generation change in paging-testing: its loader reads the page store
 * after an invalidation replaced it and throws from PageStore. The list under test is fine, so the
 * look is tried again; any other index error is a real failure and goes on.
 */
internal object PagingSnapshotRetry {
    /** How many looks one snapshot gets before the race is reported. */
    const val ATTEMPTS = 3

    fun isPageStoreRace(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is IndexOutOfBoundsException && current.stackTrace.any { frame ->
                    frame.className == "androidx.paging.PageStore" &&
                        (frame.methodName == "checkIndex" || frame.methodName == "get")
                }
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    suspend fun <T> retry(block: suspend () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (error: IndexOutOfBoundsException) {
                if (!isPageStoreRace(error) || attempt >= ATTEMPTS) throw error
                attempt++
            }
        }
    }
}

/** The retry on its own; the real app proof is the HomeViewModelTest run. */
class PagingSnapshotRetryTest {
    @Test
    fun aPageStoreIndexRaceIsRetried() = runBlocking<Unit> {
        var looks = 0
        val rows = PagingSnapshotRetry.retry {
            looks++
            if (looks == 1) throw pageStoreRace()
            listOf(2L)
        }

        assertThat(rows).containsExactly(2L)
        assertThat(looks).isEqualTo(2)
    }

    @Test
    fun anIndexErrorFromTheDaoIsNotRetried() = runBlocking<Unit> {
        var looks = 0
        val error = indexError("org.evsyukov.brook.data.db.EntryDao_Impl", "states")

        val thrown = runCatching {
            PagingSnapshotRetry.retry<Unit> {
                looks++
                throw error
            }
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(error)
        assertThat(looks).isEqualTo(1)
    }

    @Test
    fun anotherFailureIsNotRetried() = runBlocking<Unit> {
        var looks = 0
        val error = IllegalStateException("the database is closed")

        val thrown = runCatching {
            PagingSnapshotRetry.retry<Unit> {
                looks++
                throw error
            }
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(error)
        assertThat(looks).isEqualTo(1)
    }

    @Test
    fun aPageStoreRaceIsGivenUpAfterTheBound() = runBlocking<Unit> {
        var looks = 0

        val thrown = runCatching {
            PagingSnapshotRetry.retry<Unit> {
                looks++
                throw pageStoreRace()
            }
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IndexOutOfBoundsException::class.java)
        assertThat(looks).isEqualTo(PagingSnapshotRetry.ATTEMPTS)
    }

    private fun pageStoreRace() = indexError("androidx.paging.PageStore", "checkIndex")

    private fun indexError(className: String, method: String) = IndexOutOfBoundsException("Index: 1, Size: 1").apply {
        stackTrace = arrayOf(StackTraceElement(className, method, "Source.kt", 60))
    }
}
