package com.marvin.sport.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marvin.sport.data.ExerciseInfo
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseInfoSheet(info: ExerciseInfo, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                info.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Technique",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(info.description, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    val query = URLEncoder.encode(info.searchQuery, "UTF-8")
                    val uri = Uri.parse("https://www.youtube.com/results?search_query=$query")
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(Icons.Filled.PlayCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Voir la démo (YouTube)")
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
