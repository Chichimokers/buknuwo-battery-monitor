package com.buknuwo.battery.di

import com.buknuwo.battery.bluetooth.AndroidBleManager
import com.buknuwo.battery.bluetooth.BleManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BluetoothModule {
    @Binds
    @Singleton
    abstract fun bindBleManager(implementation: AndroidBleManager): BleManager
}
