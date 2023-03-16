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

package com.android.systemui.statusbar.pipeline.mobile.ui

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.phone.StatusBarIconController
import com.android.systemui.statusbar.phone.StatusBarIconController.IconManager
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * This class is intended to provide a context to collect on the
 * [MobileIconsInteractor.filteredSubscriptions] data source and supply a state flow that can
 * control [StatusBarIconController] to keep the old UI in sync with the new data source.
 *
 * It also provides a mechanism to create a top-level view model for each IconManager to know about
 * the list of available mobile lines of service for which we want to show icons.
 */
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class MobileUiAdapter
@Inject
constructor(
    interactor: MobileIconsInteractor,
    private val iconController: StatusBarIconController,
    private val iconsViewModelFactory: MobileIconsViewModel.Factory,
    @Application scope: CoroutineScope,
) {
    private val mobileSubIds: Flow<List<Int>> =
        interactor.filteredSubscriptions.mapLatest { infos ->
            infos.map { subscriptionInfo -> subscriptionInfo.subscriptionId }
        }

    /**
     * We expose the list of tracked subscriptions as a flow of a list of ints, where each int is
     * the subscriptionId of the relevant subscriptions. These act as a key into the layouts which
     * house the mobile infos.
     *
     * NOTE: this should go away as the view presenter learns more about this data pipeline
     */
    private val mobileSubIdsState: StateFlow<List<Int>> =
        mobileSubIds
            .onEach {
                // Notify the icon controller here so that it knows to add icons
                iconController.setNewMobileIconSubIds(it)
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), listOf())

    /**
     * Create a MobileIconsViewModel for a given [IconManager], and bind it to to the manager's
     * lifecycle. This will start collecting on [mobileSubIdsState] and link our new pipeline with
     * the old view system.
     */
    fun createMobileIconsViewModel(): MobileIconsViewModel =
        iconsViewModelFactory.create(mobileSubIdsState)
}
