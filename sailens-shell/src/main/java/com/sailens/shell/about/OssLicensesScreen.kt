package com.sailens.shell.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.sailens.shell.R
import com.sailens.shell.ext.openUrl
import com.sailens.shell.design.components.SailensScaffold
import com.sailens.shell.design.components.SettingRow
import com.sailens.shell.design.theme.SailensDimens

@Composable
fun OssLicensesScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    libraries: List<OssLibrary> = SailensOssLibraries,
) {
    val context = LocalContext.current

    SailensScaffold(
        title = stringResource(R.string.oss_title),
        onNavigateBack = onNavigateBack,
        navigateBackContentDescription = stringResource(R.string.cd_navigate_back),
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = SailensDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceXs),
        ) {
            items(libraries, key = { it.name }) { library ->
                SettingRow(
                    title = library.name,
                    valueText = library.license,
                    supportingText = library.url,
                    onClick = { context.openUrl(library.url) },
                )
            }
        }
    }
}
