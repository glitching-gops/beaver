package com.gops.spatialmapper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gops.spatialmapper.data.TreeClass

/**
 * A single-select row of FilterChips for manually choosing a tree's growth form class.
 *
 * The operator can select one of: Palm, Broadleaf, Bamboo, Other. Selecting Broadleaf
 * sets the class to BROADLEAF_UNCONFIRMED (the distinction between evergreen and deciduous
 * is reserved for the species-lookup phase). Deselecting (tapping the selected chip again)
 * returns the tree to UNCLASSIFIED. No chip is pre-selected: the UI starts empty, and the
 * operator must actively choose a class or leave it as UNCLASSIFIED.
 *
 * The row is compact and thumb-friendly: large tap targets, no forced line-wrapping on typical
 * phone widths, and positioned so the Save button below stays visible on small displays.
 *
 * @param selectedClass The currently selected [TreeClass], or null if UNCLASSIFIED.
 * @param onClassSelected Invoked when the operator selects a class. Passes the new class value.
 */
@Composable
fun TreeClassSelector(
    selectedClass: TreeClass?,
    onClassSelected: (TreeClass?) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.CenterHorizontally)
    ) {
        val chips = listOf(
            "Palm" to TreeClass.PALM,
            "Broadleaf" to TreeClass.BROADLEAF_UNCONFIRMED,
            "Bamboo" to TreeClass.BAMBOO,
            "Other" to TreeClass.OTHER
        )

        chips.forEach { (label, treeClass) ->
            FilterChip(
                selected = selectedClass == treeClass,
                onClick = {
                    // Toggle: if already selected, deselect (set to UNCLASSIFIED); otherwise select.
                    onClassSelected(if (selectedClass == treeClass) null else treeClass)
                },
                label = { Text(label) },
                modifier = Modifier.padding(4.dp)
            )
        }
    }
}
