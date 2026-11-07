package com.revitvisualizer.app.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.revitvisualizer.app.advanced.LightingAnalysisReport

@Composable
fun LightingAnalysisReview(report: LightingAnalysisReport) {
    var show by rememberSaveable(report) { mutableStateOf(false) }
    TextButton({ show = true }) { Text("Review lighting analysis") }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text("Lighting analysis") }, text = {
        SelectionContainer {
            Text(report.orientation.review(report.environment), Modifier.verticalScroll(rememberScrollState()))
        }
    }, confirmButton = { TextButton({ show = false }) { Text("Close") } })
}
