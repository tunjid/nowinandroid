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

import com.google.samples.apps.nowinandroid.core.data.repository.NewsResourceQuery
import com.google.samples.apps.nowinandroid.core.data.repository.UserDataRepository
import com.google.samples.apps.nowinandroid.core.domain.GetSaveableNewsResourcesUseCase
import com.google.samples.apps.nowinandroid.core.domain.model.SaveableNewsResource
import com.google.samples.apps.nowinandroid.feature.foryou.ForYouItem.News
import com.tunjid.tiler.ListTiler
import com.tunjid.tiler.Tile
import com.tunjid.tiler.Tile.Limiter
import com.tunjid.tiler.TiledList
import com.tunjid.tiler.listTiler
import com.tunjid.tiler.toTiledList
import com.tunjid.tiler.utilities.PivotRequest
import com.tunjid.tiler.utilities.toPivotedTileInputs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

// Fetch 20 news resources per query
const val ITEMS_PER_QUERY = 20

// Limit items sent to the UI for rendering to just 50 items.
// This covers the range of a user's fling
private const val MAX_ITEMS_IN_UI = 50

// Allows concurrent queries to the db to settle before emitting items
private const val QUERY_DEBOUNCE = 200L

fun tiledForYouItems(
    gridSpans: Flow<Int>,
    scrollPositionQueries: Flow<NewsResourceQuery>,
    userDataRepository: UserDataRepository,
    getSaveableNewsResourcesUseCase: GetSaveableNewsResourcesUseCase
): Flow<TiledList<NewsResourceQuery, ForYouItem>> {
    val filteredTopicIds = userDataRepository.userData
        .map { userData ->
            // If the user hasn't completed the onboarding and hasn't selected any interests
            // show an empty news list to clearly demonstrate that their selections affect the
            // news articles they will see.
            if (!userData.shouldHideOnboarding && userData.followedTopics.isEmpty()) null
            else userData.followedTopics
        }

    val queryRequests = combine(
        scrollPositionQueries,
        filteredTopicIds,
        NewsResourceQuery::matchFilteredTopics
    )
        .distinctUntilChanged()
        // Pivot requests around the user's scroll position and the span of the grid
        .toPivotedTileInputs<NewsResourceQuery, ForYouItem>(gridSpans.map(::pivotRequest))

    // Load a dynamic amount of items to accommodate for different screen sizes.
    val limiterRequests = gridSpans
        .distinctUntilChanged()
        .map { spanCount ->
            Limiter<NewsResourceQuery, ForYouItem> { items ->
                items.size > MAX_ITEMS_IN_UI * spanCount
            }
        }

    return merge(
        queryRequests,
        limiterRequests
    )
        .toTiledList(
            forYouItemListTiler(getSaveableNewsResourcesUseCase)
        )
        // Allow db queries to settle
        .debounce(QUERY_DEBOUNCE)
}

/**
 * Change the query as a function of the user's scroll position and user's filter preferences
 */
private fun NewsResourceQuery.matchFilteredTopics(
    filteredTopicIds: Set<String>?
) = NewsResourceQuery(
    offset = if (this.filterTopicIds != filteredTopicIds) 0 else offset,
    // filterTopicIds is null if the user has not onboarded yet
    filterTopicIds = filteredTopicIds ?: emptySet(),
    // If the user has not onboarded yet, show nothing
    limit = if (filteredTopicIds == null) 0 else ITEMS_PER_QUERY
)

private fun forYouItemListTiler(
    getSaveableNewsResourcesUseCase: GetSaveableNewsResourcesUseCase
): ListTiler<NewsResourceQuery, ForYouItem> =
    listTiler(
        order = Tile.Order.Sorted(newsResourceQueryComparator),
        limiter = Limiter { items ->
            items.size > MAX_ITEMS_IN_UI
        },
        fetcher = { query ->
            getSaveableNewsResourcesUseCase.invoke(query)
                .map { saveableNewsResources ->
                    saveableNewsResources.map<SaveableNewsResource, ForYouItem>(
                        News::Loaded
                    )
                }
        }
    )

internal fun pivotRequest(maxItemSpan: Int) = PivotRequest(
    // Tile as a function of the the amount of content visible to the user at once
    onCount = 3 * maxItemSpan,
    offCount = 1 * maxItemSpan,
    nextQuery = nextArchiveQuery,
    previousQuery = previousArchiveQuery,
    comparator = newsResourceQueryComparator
)

private val newsResourceQueryComparator = compareBy(NewsResourceQuery::offset)

private val nextArchiveQuery: NewsResourceQuery.() -> NewsResourceQuery? = {
    copy(offset = offset + limit)
}

private val previousArchiveQuery: NewsResourceQuery.() -> NewsResourceQuery? = {
    if (offset == 0) null
    else copy(
        offset = maxOf(
            a = 0,
            b = offset - limit
        )
    )
}
