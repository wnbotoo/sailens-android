package com.sailens.shell.design.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Convenience scaffold for the app's secondary screens (Settings, About, debug pages): a
 * [SailensTopBar] with an optional back button plus a content slot that receives the inner padding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SailensScaffold(
    title: String,
    onNavigateBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    navigateBackContentDescription: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            SailensTopBar(
                title = title,
                onNavigateBack = onNavigateBack,
                navigateBackContentDescription = navigateBackContentDescription,
                actions = actions,
            )
        },
        content = content,
    )
}
