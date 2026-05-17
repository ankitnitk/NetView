package com.netview.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun TechBadge(networkType: String, modifier: Modifier = Modifier) {
    val color = when {
        networkType.contains("5G SA") -> Color(0xFF7B1FA2)
        networkType.contains("5G NSA") -> Color(0xFFE91E63)
        networkType.contains("5G") -> Color(0xFFD81B60)
        networkType.contains("4G") -> Color(0xFF1976D2)
        networkType.contains("3G") -> Color(0xFFFF9800)
        networkType.contains("2G") -> Color(0xFF607D8B)
        else -> Color.Gray
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = networkType,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}
