// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    @DefaultDispatcher
    @Provides
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @IoDispatcher
    @Provides
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @MainDispatcher
    @Provides
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    @OptIn(ExperimentalCoroutinesApi::class)
    @ProviderCancellationDispatcher
    @Provides
    fun provideProviderCancellationDispatcher(): CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(4)
}
