package ru.zapasli.backend.platform

import ru.zapasli.backend.database.ReadinessProbe
import java.time.Instant

data class BuildInfo(
    val version: String,
    val commit: String,
)

data class AppDependencies(
    val readinessProbe: ReadinessProbe,
    val buildInfo: BuildInfo,
    val now: () -> Instant = Instant::now,
)
