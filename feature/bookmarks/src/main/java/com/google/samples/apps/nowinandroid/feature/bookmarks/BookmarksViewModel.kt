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

package com.google.samples.apps.nowinandroid.feature.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.samples.apps.nowinandroid.core.data.repository.NewsResourceQuery
import com.google.samples.apps.nowinandroid.core.data.repository.UserDataRepository
import com.google.samples.apps.nowinandroid.core.domain.GetSaveableNewsResourcesUseCase
import com.google.samples.apps.nowinandroid.core.domain.model.SaveableNewsResource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val userDataRepository: UserDataRepository,
    getSaveableNewsResources: GetSaveableNewsResourcesUseCase
) : ViewModel() {

    // TODO: Load this in tiles
    val bookmarkItems: StateFlow<List<BookmarkItem>> = getSaveableNewsResources(
        NewsResourceQuery(
            offset = 0,
            limit = Int.MAX_VALUE
        )
    )
        .filterNot { it.isEmpty() }
        .map { newsResources -> newsResources.filter(SaveableNewsResource::isSaved) } // Only show bookmarked news resources.
        .map<List<SaveableNewsResource>, List<BookmarkItem>> { it.map(BookmarkItem::News) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = listOf(BookmarkItem.Loading)
        )

    fun removeFromSavedResources(newsResourceId: String) {
        viewModelScope.launch {
            userDataRepository.updateNewsResourceBookmark(newsResourceId, false)
        }
    }
}
