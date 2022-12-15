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

import com.google.samples.apps.nowinandroid.core.domain.GetSaveableNewsResourcesUseCase
import com.google.samples.apps.nowinandroid.core.model.data.previewNewsResources
import com.google.samples.apps.nowinandroid.core.testing.repository.TestNewsRepository
import com.google.samples.apps.nowinandroid.core.testing.repository.TestUserDataRepository
import com.google.samples.apps.nowinandroid.core.testing.util.MainDispatcherRule
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * To learn more about how this test handles Flows created with stateIn, see
 * https://developer.android.com/kotlin/flow/test#statein
 */
class BookmarksViewModelTest {
    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val userDataRepository = TestUserDataRepository()
    private val newsRepository = TestNewsRepository()
    private val getSaveableNewsResourcesUseCase = GetSaveableNewsResourcesUseCase(
        newsRepository = newsRepository,
        userDataRepository = userDataRepository
    )
    private lateinit var viewModel: BookmarksViewModel

    @Before
    fun setup() {
        viewModel = BookmarksViewModel(
            userDataRepository = userDataRepository,
            getSaveableNewsResources = getSaveableNewsResourcesUseCase
        )
    }

    @Test
    fun stateIsInitiallyLoading() = runTest {
        assertEquals(
            expected = listOf(BookmarkItem.Loading),
            actual = viewModel.bookmarkItems.value
        )
    }

    @Test
    fun oneBookmark_showsInFeed() = runTest {
        val collectJob = launch(UnconfinedTestDispatcher()) { viewModel.bookmarkItems.collect() }

        newsRepository.sendNewsResources(previewNewsResources)
        userDataRepository.updateNewsResourceBookmark(previewNewsResources[0].id, true)
        val item = viewModel.bookmarkItems.value.first()
        assertIs<BookmarkItem.News>(item)
        assertEquals(viewModel.bookmarkItems.value.size, 1)

        collectJob.cancel()
    }

    @Test
    fun oneBookmark_whenRemoving_removesFromFeed() = runTest {
        val collectJob = launch(UnconfinedTestDispatcher()) { viewModel.bookmarkItems.collect() }
        // Set the news resources to be used by this test
        newsRepository.sendNewsResources(previewNewsResources)
        // Start with the resource saved
        userDataRepository.updateNewsResourceBookmark(previewNewsResources[0].id, true)
        // Use viewModel to remove saved resource
        viewModel.removeFromSavedResources(previewNewsResources[0].id)
        // Verify list of saved resources is now empty
        assertEquals(
            expected = emptyList(),
            actual = viewModel.bookmarkItems.value
        )

        collectJob.cancel()
    }
}
