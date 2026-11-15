package com.maaframework.android.project

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** A release is one indivisible project + runtime distribution. No desktop fallback. */
@Serializable
data class GitHubReleaseConfig(
    val owner: String,
    val repo: String,
    @SerialName("asset_pattern") val assetPattern: String,
    @SerialName("bundled_version") val bundledVersion: String,
    val mappings: List<ReleaseArchiveMapping>,
    val checks: List<ReleaseJsonCheck> = emptyList(),
    @SerialName("required_paths") val requiredPaths: List<String>,
    @SerialName("executable_paths") val executablePaths: List<String> = emptyList(),
    @SerialName("max_expanded_bytes") val maxExpandedBytes: Long = 3L * 1024 * 1024 * 1024,
)

@Serializable
data class ReleaseArchiveMapping(
    /** Nested archive entry names, from outside to inside. */
    val archives: List<String> = emptyList(),
    /** Directory prefixes end with '/'; other paths name a single file. */
    val source: String = "",
    val target: String = "",
    val include: String = ".*",
    val exclude: String? = null,
)

@Serializable
data class ReleaseJsonCheck(val file: String, val path: List<String>, val expected: JsonElement)
