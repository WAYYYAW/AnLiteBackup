package com.anlite.backup.core

import com.anlite.backup.core.engine.ResticDriver
import com.anlite.backup.core.queue.SequentialBackupQueue
import com.anlite.backup.core.usecase.BackupAppUseCase
import com.anlite.backup.core.usecase.BackupDirectoryUseCase
import com.anlite.backup.core.usecase.DeleteSnapshotUseCase
import com.anlite.backup.core.usecase.PruneRepositoryUseCase
import com.anlite.backup.core.usecase.RestoreAppUseCase
import com.anlite.backup.core.usecase.RestoreDirectoryUseCase
import com.anlite.backup.core.usecase.SyncRepositoryUseCase
import com.anlite.backup.data.preferences.EnginePreferences
import com.anlite.backup.data.repository.DirectoryRepository
import com.anlite.backup.data.repository.PackageRepository
import com.anlite.backup.ui.viewmodel.AppsViewModel
import com.anlite.backup.ui.viewmodel.DirectoriesViewModel
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
    single { SyncRepositoryUseCase(androidContext(), get(), get(), get(), get()) }
    single { PruneRepositoryUseCase(get(), get()) }
    single { DeleteSnapshotUseCase(get(), get(), get(), get(), get()) }
    single { SequentialBackupQueue(get(), get(), get(), get(), get()) }
    single { PackageRepository(androidContext(), get(), get(), getOrNull()) }
    single { AppsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { DirectoriesViewModel(get(), get(), get(), get(), get(), get(), get()) }
}
