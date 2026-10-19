package org.evsyukov.brook.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import org.evsyukov.brook.data.account.AccountManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BackgroundWorkTest {
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun periodicSyncIsOneWorkThatANewIntervalReplaces() {
        BackgroundWork.schedulePeriodicSync(workManager, 30)
        BackgroundWork.schedulePeriodicSync(workManager, 60)

        val infos = workManager.getWorkInfosForUniqueWork(BackgroundWork.SYNC).get()
        assertThat(infos).hasSize(1)
        assertThat(infos.single().periodicityInfo!!.repeatIntervalMillis).isEqualTo(TimeUnit.MINUTES.toMillis(60))
        assertThat(infos.single().constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }

    @Test
    fun prefetchWaitsForTheNetworkTheSettingsAllowAndCarriesTheAccountTag() {
        BackgroundWork.enqueuePrefetch(workManager, "a1", onlyUnmetered = true)
        val wifi = workManager.getWorkInfosForUniqueWork(BackgroundWork.prefetchName("a1")).get().single()
        assertThat(wifi.constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(wifi.tags).contains(AccountManager.workTag("a1"))

        BackgroundWork.enqueuePrefetch(workManager, "a1", onlyUnmetered = false)
        val any = workManager.getWorkInfosForUniqueWork(BackgroundWork.prefetchName("a1")).get().filterNot { it.state.isFinished }
        assertThat(any).hasSize(1)
        assertThat(any.single().constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }

    @Test
    fun removingTheAccountCancelsItsPrefetch() {
        BackgroundWork.enqueuePrefetch(workManager, "a1", onlyUnmetered = true)
        BackgroundWork.enqueuePrefetch(workManager, "a2", onlyUnmetered = true)

        workManager.cancelAllWorkByTag(AccountManager.workTag("a1")).result.get()

        assertThat(workManager.getWorkInfosForUniqueWork(BackgroundWork.prefetchName("a1")).get().single().state.isFinished).isTrue()
        assertThat(workManager.getWorkInfosForUniqueWork(BackgroundWork.prefetchName("a2")).get().single().state.isFinished).isFalse()
    }
}
