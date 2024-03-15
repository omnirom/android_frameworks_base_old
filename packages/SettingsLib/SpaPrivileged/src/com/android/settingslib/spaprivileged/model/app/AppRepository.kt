/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settingslib.spaprivileged.model.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.util.IconDrawableFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.android.settingslib.spa.framework.compose.rememberContext
import com.android.settingslib.spaprivileged.R
import com.android.settingslib.spaprivileged.framework.common.userManager
import com.android.settingslib.spaprivileged.framework.compose.placeholder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun rememberAppRepository(): AppRepository = rememberContext(::AppRepositoryImpl)

interface AppRepository {
    fun loadLabel(app: ApplicationInfo): String

    @Composable
    fun produceLabel(app: ApplicationInfo, isClonedAppPage: Boolean = false): State<String> {
        val context = LocalContext.current
        return produceState(initialValue = placeholder(), app) {
            withContext(Dispatchers.IO) {
                value = if (isClonedAppPage || isCloneApp(context, app)) {
                    context.getString(R.string.cloned_app_info_label, loadLabel(app))
                } else {
                    loadLabel(app)
                }
            }
        }
    }

    private fun isCloneApp(context: Context, app: ApplicationInfo): Boolean {
        val userInfo = context.userManager.getUserInfo(app.userId)
        return userInfo != null && userInfo.isCloneProfile
    }

    @Composable
    fun produceIcon(app: ApplicationInfo): State<Drawable?>

    @Composable
    fun produceIconContentDescription(app: ApplicationInfo): State<String?>
}

internal class AppRepositoryImpl(private val context: Context) : AppRepository {
    private val packageManager = context.packageManager
    private val iconDrawableFactory = IconDrawableFactory.newInstance(context)

    override fun loadLabel(app: ApplicationInfo): String = app.loadLabel(packageManager).toString()

    @Composable
    override fun produceIcon(app: ApplicationInfo) =
        produceState<Drawable?>(initialValue = null, app) {
            withContext(Dispatchers.IO) {
                value = iconDrawableFactory.getBadgedIcon(app)
            }
        }

    @Composable
    override fun produceIconContentDescription(app: ApplicationInfo) =
        produceState<String?>(initialValue = null, app) {
            withContext(Dispatchers.IO) {
                value = when {
                    context.userManager.isManagedProfile(app.userId) -> {
                        context.getString(com.android.settingslib.R.string.category_work)
                    }

                    else -> null
                }
            }
        }
}
