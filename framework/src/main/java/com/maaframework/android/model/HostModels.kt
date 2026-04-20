package com.maaframework.android.model

import kotlinx.serialization.Serializable

@Serializable
data class RootBinaryProbe(
    val path: String,
    val exists: Boolean = false,
    val executableByApp: Boolean = false,
)

@Serializable
data class RootEnvironmentReport(
    val available: Boolean = false,
    val granted: Boolean = false,
    val summary: String = "",
    val binaryProbes: List<RootBinaryProbe> = emptyList(),
)
