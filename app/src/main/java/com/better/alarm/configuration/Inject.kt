package com.better.alarm.configuration

import android.content.ComponentCallbacks
import org.koin.core.context.GlobalContext.get
import org.koin.core.parameter.ParametersDefinition
import org.koin.core.qualifier.Qualifier

/**
 * globalInject lazily given dependency for Android koincomponent
 * @param qualifier - bean qualifier / optional
 * @param scope
 * @param parameters - injection parameters
 */
inline fun <reified T : Any> ComponentCallbacks.globalInject(
        qualifier: Qualifier? = null,
        noinline parameters: ParametersDefinition? = null
) = lazy { get().koin.rootScope.get<T>(qualifier, parameters) }