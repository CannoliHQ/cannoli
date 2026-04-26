package dev.cannoli.scorza.di

import javax.inject.Qualifier

@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class IoScope
@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class CannoliRoot
@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class RomDir
