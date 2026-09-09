package tech.whitewolf.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import tech.whitewolf.app.subapp.SubAppEntry
import tech.whitewolf.app.subapp.SubAppId

/** The suite home: one tile per registered sub-app, in registration order. */
@Composable
fun LauncherScreen(
    entries: List<SubAppEntry>,
    onOpen: (SubAppId) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(entries, key = { it.id.value }) { entry ->
            Card(
                onClick = { onOpen(entry.id) },
                modifier = Modifier.testTag("tile.${entry.id.value}"),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(entry.ui.icon, contentDescription = null, modifier = Modifier.size(36.dp))
                    Text(
                        entry.ui.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}
