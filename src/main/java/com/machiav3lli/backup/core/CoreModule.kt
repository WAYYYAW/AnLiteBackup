package com.machiav3lli.backup.core

import com.machiav3lli.backup.core.engine.ResticDriver
import com.machiav3lli.backup.core.queue.SequentialBackupQueue
import com.machiav3lli.backup.core.usecase.BackupAppUseCase
import com.machiav3lli.backup.core.usecase.BackupDirectoryUseCase
import com.machiav3lli.backup.core.usecase.DeleteSnapshotUseCase
import com.machiav3lli.backup.core.usecase.PruneRepositoryUseCase
import com.machiav3lli.backup.core.usecase.RestoreAppUseCase
import com.machiav3lli.backup.core.usecase.RestoreDirectoryUseCase
import com.machiav3lli.backup.core.usecase.SyncRepositoryUseCase
import com.machiav3lli.backup.data.preferences.EnginePreferences
import com.machiav3lli.backup.data.repository.DirectoryRepository
import com.machiav3lli.backup.data.repository.PackageRepository
import com.machiav3lli.backup.ui.viewmodel.AppsViewModel
import com.machiav3lli.backup.ui.viewmodel.DirectoriesViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val coreModule = module {
    single { EnginePreferences(androidContext()) }
    single { ResticDriver(androidContext()) }
    single { DirectoryRepository(get()) }
    single { BackupAppUseCase(androidContext(), get(), get(), get()) }
    single { RestoreAppUseCase(androidContext(), get(), get()) }
    single { BackupDirectoryUseCase(get(), get(), get()) }
    single { RestoreDirectoryUseCase(get(), get()) }
    single { SyncRepositoryUseCase(androidContext(), get(), get(), get()) }
    single { PruneRepositoryUseCase(get(), get()) }
    single { DeleteSnapshotUseCase(get(), get(), get(), get(), get()) }
    single { SequentialBackupQueue(get(), get(), get(), get(), get()) }
    single { PackageRepository(androidContext(), get(), get(), getOrNull()) }
    single { AppsViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { DirectoriesViewModel(get(), get(), get(), get(), get(), get()) }
}
