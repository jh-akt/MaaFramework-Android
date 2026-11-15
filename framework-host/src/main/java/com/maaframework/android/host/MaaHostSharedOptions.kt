package com.maaframework.android.host

import com.maaframework.android.catalog.MaaOptionOverrides.buildOptionOverride
import com.maaframework.android.catalog.MaaOptionOverrides.mergeOverrideJson
import com.maaframework.android.catalog.TaskOptionSupport
import com.maaframework.android.model.CatalogSnapshot
import com.maaframework.android.model.TaskOptionDescriptor

/** Global and resource options have separate namespaces, even when their IDs match. */
object MaaHostSharedOptions {
    fun scopes(catalog: CatalogSnapshot): Map<String, List<TaskOptionDescriptor>> = buildMap {
        put(MaaHostOptionScopes.GLOBAL, catalog.globalOptions)
        catalog.resources.forEach { put(MaaHostOptionScopes.resource(it.id), it.options) }
    }

    private fun activeScopes(state: MaaHostState, resourceId: String?) = linkedMapOf(
        MaaHostOptionScopes.GLOBAL to state.catalog.globalOptions,
    ).apply {
        state.catalog.resources.firstOrNull { it.id == resourceId }?.let {
            put(MaaHostOptionScopes.resource(it.id), it.options)
        }
    }

    fun buildOverride(state: MaaHostState, resourceId: String?): String? =
        activeScopes(state, resourceId).entries.fold(null as String?) { merged, (scope, options) ->
            mergeOverrideJson(merged, buildOptionOverride(
                options = TaskOptionSupport.filterOptionsForResource(options, resourceId),
                selectedByOption = state.sharedOptionSelectionsByScope[scope].orEmpty(),
                inputValuesByOption = state.sharedInputValuesByScope[scope].orEmpty(),
            ))
        }

    fun validationError(state: MaaHostState, resourceId: String?): String? {
        activeScopes(state, resourceId).forEach { (scope, options) ->
            val errors = TaskOptionSupport.collectInputValidationErrors(
                options = TaskOptionSupport.filterOptionsForResource(options, resourceId),
                selectedByOption = state.sharedOptionSelectionsByScope[scope].orEmpty(),
                inputValuesByOption = state.sharedInputValuesByScope[scope].orEmpty(),
            )
            if (errors.isNotEmpty()) return if (scope == MaaHostOptionScopes.GLOBAL) {
                "全局配置里有未通过校验的输入项"
            } else {
                "资源包配置里有未通过校验的输入项"
            }
        }
        return null
    }
}
