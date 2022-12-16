/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.samples.apps.nowinandroid.feature.foryou

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.samples.apps.nowinandroid.core.data.repository.NewsResourceQuery
import com.google.samples.apps.nowinandroid.core.data.repository.UserDataRepository
import com.google.samples.apps.nowinandroid.core.data.util.SyncStatusMonitor
import com.google.samples.apps.nowinandroid.core.domain.GetFollowableTopicsUseCase
import com.google.samples.apps.nowinandroid.core.domain.GetSaveableNewsResourcesUseCase
import com.tunjid.tiler.TiledList
import com.tunjid.tiler.buildTiledList
import com.tunjid.tiler.emptyTiledList
import com.tunjid.tiler.filterTransform
import com.tunjid.tiler.tiledListOf
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ForYouViewModel @Inject constructor(
    syncStatusMonitor: SyncStatusMonitor,
    private val userDataRepository: UserDataRepository,
    getSaveableNewsResources: GetSaveableNewsResourcesUseCase,
    getFollowableTopics: GetFollowableTopicsUseCase
) : ViewModel() {

    private val scrollPositionQueries = MutableStateFlow(
        NewsResourceQuery(
            offset = 0,
            limit = ITEMS_PER_QUERY
        )
    )

    private val maxGridSpans = MutableStateFlow(1)

    private val shouldShowOnboarding: Flow<Boolean> =
        userDataRepository.userData.map { !it.shouldHideOnboarding }

    val isSyncing = syncStatusMonitor.isSyncing
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    private val onboardingContent =
        combine(
            shouldShowOnboarding,
            getFollowableTopics()
        ) { shouldShowOnboarding, topics ->
            if (shouldShowOnboarding) OnboardingUiState.Shown(topics = topics)
            else OnboardingUiState.NotShown
        }
            .onStart {
                emit(OnboardingUiState.Loading)
            }
            .map {
                // Add onboarding item if it should show
                ForYouItemContent(
                    onboardingItems = if (it is OnboardingUiState.NotShown) emptyTiledList()
                    else tiledListOf(scrollPositionQueries.value to ForYouItem.OnBoarding(it)),
                )
            }

    private val newsContent = tiledForYouItems(
        gridSpans = maxGridSpans,
        scrollPositionQueries = scrollPositionQueries,
        userDataRepository = userDataRepository,
        getSaveableNewsResourcesUseCase = getSaveableNewsResources
    )
        .map { tiledNewsItems ->
            // These are at most MAX_ITEMS_IN_UI present, making this a very cheap operation
            ForYouItemContent(
                newsItems = tiledNewsItems
                    // Make items distinct by key as duplicates may across queries exist when new
                    // items are written to the db and flows are updated
                    .filterTransform { distinctBy(ForYouItem::key) },
            )
        }

    private val forYouItemContent = merge(
        onboardingContent,
        newsContent
    )
        .scan(
            ForYouItemContent(
                onboardingItems = tiledListOf(
                    scrollPositionQueries.value to ForYouItem.OnBoarding(OnboardingUiState.Loading)
                ),
                newsItems = tiledListOf(
                    scrollPositionQueries.value to ForYouItem.News.Loading
                )
            )
        ) { old, new ->
            old.copy(
                onboardingItems = new.onboardingItems ?: old.onboardingItems,
                newsItems = new.newsItems ?: old.newsItems
            )
        }

    val forYouItems: StateFlow<TiledList<NewsResourceQuery, ForYouItem>> =
        forYouItemContent.map { (onboardingItems, newsItems) ->
            buildTiledList {
                onboardingItems?.forEachIndexed { index, forYouItem ->
                    add(onboardingItems.queryAt(index), forYouItem)
                }
                newsItems?.forEachIndexed { index, forYouItem ->
                    add(newsItems.queryAt(index), forYouItem)
                }
            }
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = tiledListOf(
                    scrollPositionQueries.value to ForYouItem.OnBoarding(OnboardingUiState.Loading),
                    scrollPositionQueries.value to ForYouItem.News.Loading,
                )
            )

    fun updateTopicSelection(topicId: String, isChecked: Boolean) {
        viewModelScope.launch {
            userDataRepository.toggleFollowedTopicId(topicId, isChecked)
        }
    }

    fun updateNewsResourceSaved(newsResourceId: String, isChecked: Boolean) {
        viewModelScope.launch {
            userDataRepository.updateNewsResourceBookmark(newsResourceId, isChecked)
        }
    }

    fun dismissOnboarding() {
        viewModelScope.launch {
            userDataRepository.setShouldHideOnboarding(true)
        }
    }

    fun onVisibleQueryChanged(query: NewsResourceQuery) {
        scrollPositionQueries.value = query
    }

    fun onGridSpanChanged(maxItemSpan: Int) {
        maxGridSpans.value = maxItemSpan
    }
}

private data class ForYouItemContent(
    val onboardingItems: TiledList<NewsResourceQuery, ForYouItem>? = null,
    val newsItems: TiledList<NewsResourceQuery, ForYouItem>? = null
)
