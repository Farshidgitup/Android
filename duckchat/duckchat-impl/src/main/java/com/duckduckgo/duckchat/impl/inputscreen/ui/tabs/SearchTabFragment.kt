/*
 * Copyright (c) 2025 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.duckchat.impl.inputscreen.ui.tabs

import android.os.Build.VERSION
import android.os.Bundle
import android.view.View
import android.view.View.OVER_SCROLL_NEVER
import android.view.ViewTreeObserver
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.duckduckgo.anvil.annotations.InjectWith
import com.duckduckgo.app.browser.omnibar.OmnibarType
import com.duckduckgo.browser.api.autocomplete.AutoComplete.AutoCompleteSuggestion
import com.duckduckgo.browser.api.ui.BrowserScreens.PrivateSearchScreenNoParams
import com.duckduckgo.browser.ui.autocomplete.BrowserAutoCompleteSuggestionsAdapter
import com.duckduckgo.common.ui.DuckDuckGoFragment
import com.duckduckgo.common.ui.view.dialog.TextAlertDialogBuilder
import com.duckduckgo.common.ui.view.toPx
import com.duckduckgo.common.ui.viewbinding.viewBinding
import com.duckduckgo.common.utils.FragmentViewModelFactory
import com.duckduckgo.di.scopes.FragmentScope
import com.duckduckgo.duckchat.impl.R
import com.duckduckgo.duckchat.impl.databinding.FragmentSearchTabBinding
import com.duckduckgo.duckchat.impl.inputscreen.ui.InputScreenConfigResolver
import com.duckduckgo.duckchat.impl.inputscreen.ui.InputScreenFragment
import com.duckduckgo.duckchat.impl.inputscreen.ui.command.SearchCommand
import com.duckduckgo.duckchat.impl.inputscreen.ui.command.SearchCommand.RestoreAutoCompleteScrollPosition
import com.duckduckgo.duckchat.impl.inputscreen.ui.command.SearchCommand.ShowRemoveSearchSuggestionDialog
import com.duckduckgo.duckchat.impl.inputscreen.ui.view.BottomBlurView
import com.duckduckgo.duckchat.impl.inputscreen.ui.view.RecyclerBottomSpacingDecoration
import com.duckduckgo.duckchat.impl.inputscreen.ui.viewmodel.InputScreenViewModel
import com.duckduckgo.navigation.api.GlobalActivityStarter
import com.duckduckgo.newtabpage.api.NewTabPageProvider
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt
import com.duckduckgo.browser.ui.R as BrowserUI

@InjectWith(FragmentScope::class)
class SearchTabFragment : DuckDuckGoFragment(R.layout.fragment_search_tab) {

    @Inject lateinit var viewModelFactory: FragmentViewModelFactory

    @Inject lateinit var globalActivityStarter: GlobalActivityStarter

    @Inject lateinit var inputScreenConfigResolver: InputScreenConfigResolver

    @Inject lateinit var newTabPageProvider: NewTabPageProvider

    private val viewModel: InputScreenViewModel by lazy {
        ViewModelProvider(requireParentFragment(), viewModelFactory)[InputScreenViewModel::class.java]
    }

    private val binding: FragmentSearchTabBinding by viewBinding()
    private lateinit var autoCompleteSuggestionsAdapter: BrowserAutoCompleteSuggestionsAdapter
    private var newTabPageView: View? = null

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        configureNewTabPage()
        configureAutoComplete()
        configureObservers()
        configureBottomBlur()
    }

    private var bottomBlurView: BottomBlurView? = null
    private var bottomBlurLayoutListener: View.OnLayoutChangeListener? = null
    private var bottomBlurDataObserver: RecyclerView.AdapterDataObserver? = null

    private fun configureBottomBlur() {
        if (VERSION.SDK_INT >= 33 && inputScreenConfigResolver.useTopBar()) {
            val recyclerView = binding.autoCompleteSuggestionsList

            // TODO: Handle overscroll when blurring
            recyclerView.overScrollMode = OVER_SCROLL_NEVER

            bottomBlurView = BottomBlurView(requireContext())
            bottomBlurView?.setTargetView(recyclerView)
            binding.autoCompleteBottomFadeContainer.addView(bottomBlurView)

            recyclerView.addOnScrollListener(
                object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        bottomBlurView?.invalidate()
                    }
                },
            )

            bottomBlurLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                bottomBlurView?.invalidate()
            }
            recyclerView.addOnLayoutChangeListener(bottomBlurLayoutListener)

            bottomBlurDataObserver = object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() {
                    recyclerView.post { bottomBlurView?.invalidate() }
                }
                override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                    recyclerView.post { bottomBlurView?.invalidate() }
                }
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    recyclerView.post { bottomBlurView?.invalidate() }
                }
                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                    recyclerView.post { bottomBlurView?.invalidate() }
                }
            }
            recyclerView.adapter?.registerAdapterDataObserver(bottomBlurDataObserver!!)
        }
    }

    private fun configureNewTabPage() {
        // TODO: fix favorites click source to "focused state" instead of "new tab page"
        val parentFragment = requireParentFragment() as InputScreenFragment
        val favoritesContainer = parentFragment.getFavoritesContainer()

        lifecycleScope.launch {
            newTabPageProvider.provideNewTabPageVersion().firstOrNull()?.let { plugin ->
                newTabPageView =
                    plugin.getView(requireContext(), showLogo = false) { hasContent ->
                        if (isAdded && view != null) {
                            viewModel.onNewTabPageContentChanged(hasContent)
                            parentFragment.onFavoritesContentChanged(hasContent)
                        }
                    }
                favoritesContainer.addView(newTabPageView)
            }
        }
    }

    private fun configureAutoComplete() {
        val context = context ?: return

        binding.autoCompleteSuggestionsList.apply {
            layoutManager = LinearLayoutManager(context)
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(com.duckduckgo.mobile.android.R.attr.daxColorBrowserOverlay, typedValue, true)
            setBackgroundColor(typedValue.data)

            if (inputScreenConfigResolver.useTopBar()) {
                val spacing = resources.getDimensionPixelSize(R.dimen.inputScreenAutocompleteListBottomSpace)
                val decoration = RecyclerBottomSpacingDecoration(spacing)
                addItemDecoration(decoration)
                updatePadding(top = 8f.toPx(context).roundToInt())
            }
        }

        autoCompleteSuggestionsAdapter =
            BrowserAutoCompleteSuggestionsAdapter(
                immediateSearchClickListener = {
                    viewModel.userSelectedAutocomplete(it)
                },
                editableSearchClickListener = {
                    viewModel.onUserSelectedToEditQuery(it.phrase)
                },
                autoCompleteInAppMessageDismissedListener = {
                    viewModel.onUserDismissedAutoCompleteInAppMessage()
                },
                autoCompleteOpenSettingsClickListener = {
                    viewModel.onUserDismissedAutoCompleteInAppMessage()
                    globalActivityStarter.start(context, PrivateSearchScreenNoParams)
                },
                autoCompleteLongPressClickListener = {
                    viewModel.userLongPressedAutocomplete(it)
                },
                omnibarType =
                if (inputScreenConfigResolver.useTopBar()) {
                    OmnibarType.SINGLE_TOP
                } else {
                    OmnibarType.SINGLE_BOTTOM
                },
            )
        binding.autoCompleteSuggestionsList.adapter = autoCompleteSuggestionsAdapter
    }

    private fun configureObservers() {
        val parentFragment = requireParentFragment() as InputScreenFragment

        viewModel.visibilityState
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { state ->
                binding.autoCompleteSuggestionsList.isVisible = state.autoCompleteSuggestionsVisible
                binding.autoCompleteBottomFadeContainer.isVisible = state.autoCompleteSuggestionsVisible

                // Ensure ViewPager stays enabled when autocomplete is visible
                if (state.autoCompleteSuggestionsVisible) {
                    parentFragment.getViewPager().isUserInputEnabled = true
                }

                if (!state.autoCompleteSuggestionsVisible) {
                    viewModel.autoCompleteSuggestionsGone()
                }
            }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.autoCompleteSuggestionResults
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { results ->
                autoCompleteSuggestionsAdapter.updateData(results.query, results.suggestions)
            }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.searchTabCommand.observe(viewLifecycleOwner) {
            processCommand(it)
        }
    }

    private fun processCommand(command: SearchCommand) {
        when (command) {
            is ShowRemoveSearchSuggestionDialog -> showRemoveSearchSuggestionDialog(command.suggestion)
            is RestoreAutoCompleteScrollPosition ->
                restoreAutoCompleteScrollPosition(
                    command.firstVisibleItemPosition,
                    command.itemOffsetTop,
                )
        }
    }

    private fun showRemoveSearchSuggestionDialog(suggestion: AutoCompleteSuggestion) {
        storeAutocompletePosition()

        TextAlertDialogBuilder(requireContext())
            .setTitle(BrowserUI.string.autocompleteRemoveItemTitle)
            .setCancellable(true)
            .setPositiveButton(BrowserUI.string.autocompleteRemoveItemRemove)
            .setNegativeButton(BrowserUI.string.autocompleteRemoveItemCancel)
            .addEventListener(
                object : TextAlertDialogBuilder.EventListener() {
                    override fun onPositiveButtonClicked() {
                        viewModel.onRemoveSearchSuggestionConfirmed(suggestion)
                        viewModel.restoreAutoCompleteScrollPosition()
                    }

                    override fun onNegativeButtonClicked() {
                        viewModel.restoreAutoCompleteScrollPosition()
                    }

                    override fun onDialogCancelled() {
                        viewModel.restoreAutoCompleteScrollPosition()
                    }
                },
            ).show()
    }

    private fun storeAutocompletePosition() {
        val layoutManager = binding.autoCompleteSuggestionsList.layoutManager as LinearLayoutManager
        val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()
        val itemOffsetTop = layoutManager.findViewByPosition(firstVisibleItemPosition)?.top ?: 0
        viewModel.storeAutoCompleteScrollPosition(firstVisibleItemPosition, itemOffsetTop)
    }

    private fun restoreAutoCompleteScrollPosition(
        position: Int,
        offset: Int,
    ) {
        val layoutListener =
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    binding.autoCompleteSuggestionsList.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    scrollToPositionWithOffset(position, offset)
                }
            }
        binding.autoCompleteSuggestionsList.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    private fun scrollToPositionWithOffset(
        position: Int,
        offset: Int,
    ) {
        val layoutManager = binding.autoCompleteSuggestionsList.layoutManager as LinearLayoutManager
        layoutManager.scrollToPositionWithOffset(position, offset)
    }

    override fun onDestroyView() {
        binding.autoCompleteSuggestionsList.clearOnScrollListeners()
        bottomBlurLayoutListener?.let { listener ->
            binding.autoCompleteSuggestionsList.removeOnLayoutChangeListener(listener)
        }
        bottomBlurLayoutListener = null
        bottomBlurDataObserver?.let { observer ->
            binding.autoCompleteSuggestionsList.adapter?.unregisterAdapterDataObserver(observer)
        }
        bottomBlurDataObserver = null
        binding.autoCompleteBottomFadeContainer.removeAllViews()
        bottomBlurView = null
        (newTabPageView?.parent as? androidx.constraintlayout.widget.ConstraintLayout)?.removeView(newTabPageView)
        newTabPageView = null
        super.onDestroyView()
    }
}
