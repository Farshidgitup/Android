/*
 * Copyright (c) 2026 DuckDuckGo
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

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.duckduckgo.anvil.annotations.InjectWith
import com.duckduckgo.common.ui.DuckDuckGoFragment
import com.duckduckgo.common.ui.viewbinding.viewBinding
import com.duckduckgo.common.utils.FragmentViewModelFactory
import com.duckduckgo.di.scopes.FragmentScope
import com.duckduckgo.duckchat.impl.R
import com.duckduckgo.duckchat.impl.databinding.FragmentChatTabBinding
import com.duckduckgo.duckchat.impl.inputscreen.ui.suggestions.ChatSuggestionsAdapter
import com.duckduckgo.duckchat.impl.inputscreen.ui.viewmodel.InputScreenViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import logcat.logcat
import javax.inject.Inject

@InjectWith(FragmentScope::class)
class ChatTabFragment : DuckDuckGoFragment(R.layout.fragment_chat_tab) {

    @Inject
    lateinit var viewModelFactory: FragmentViewModelFactory

    private val viewModel: InputScreenViewModel by lazy {
        ViewModelProvider(requireParentFragment(), viewModelFactory)[InputScreenViewModel::class.java]
    }

    private val binding: FragmentChatTabBinding by viewBinding()
    private lateinit var chatSuggestionsAdapter: ChatSuggestionsAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupChatSuggestions()
        observeChatSuggestions()
    }

    private fun setupChatSuggestions() {
        chatSuggestionsAdapter = ChatSuggestionsAdapter { suggestion ->
            // TODO: Handle navigation to chat in duck.ai fullscreen mode
            logcat { "Chat suggestion clicked: chatId=${suggestion.chatId}, title=${suggestion.title}" }
        }

        binding.chatSuggestionsRecyclerView.adapter = chatSuggestionsAdapter
    }

    private fun observeChatSuggestions() {
        viewModel.chatSuggestions
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { suggestions ->
                val hasSuggestions = suggestions.isNotEmpty()
                binding.chatSuggestionsRecyclerView.isVisible = hasSuggestions
                binding.recentChatsHeader.isVisible = hasSuggestions
                chatSuggestionsAdapter.submitList(suggestions)
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }
}
