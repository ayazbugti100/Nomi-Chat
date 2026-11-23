package com.example.ui.components

import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.model.MainBottomTab
import com.example.ui.theme.EmeraldBright
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldDeep
import com.example.ui.theme.EmeraldMid
import com.example.ui.theme.EmeraldVibrant
import com.example.ui.theme.GoldCrown
import com.example.ui.theme.LoginGreenStart
import com.example.ui.theme.MintLight

@Composable
fun EmeraldWaveHeader(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val wave1 = remember { Path() }
    val wave2 = remember { Path() }
    Box(
        modifier = modifier.background(
            brush = Brush.linearGradient(
                colors = listOf(EmeraldDeep, EmeraldMid, EmeraldBright)
            )
        )
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height

            // Soft glassy highlight orb top-right
            drawCircle(
                color = Color.White.copy(alpha = 0.11f),
                radius = w * 0.28f,
                center = Offset(w * 0.88f, h * 0.12f)
            )

            // Translucent layered wave ribbon
            wave1.reset()
            wave1.apply {
                moveTo(0f, h * 0.65f)
                cubicTo(w * 0.25f, h * 0.35f, w * 0.65f, h * 0.92f, w, h * 0.50f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(wave1, color = Color.White.copy(alpha = 0.08f))

            wave2.reset()
            wave2.apply {
                moveTo(0f, h * 0.30f)
                cubicTo(w * 0.35f, h * 0.62f, w * 0.72f, h * 0.15f, w, h * 0.42f)
                lineTo(w, 0f)
                lineTo(0f, 0f)
                close()
            }
            drawPath(wave2, color = EmeraldDeep.copy(alpha = 0.22f))
        }
        content()
    }
}

@Composable
fun AvatarCircle(
    name: String,
    photoData: String? = null,
    isGroup: Boolean = false,
    isChannel: Boolean = false,
    showOnlineDot: Boolean = false,
    hasStoryRing: Boolean = false,
    storyRingSeen: Boolean = false,
    size: Dp = 50.dp,
    borderColor: Color? = null,
    modifier: Modifier = Modifier
) {
    val decodedBytes = remember(photoData) {
        if (!photoData.isNullOrBlank() && photoData.startsWith("data:image")) {
            val b64 = photoData.substringAfter("base64,", "")
            runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
        } else null
    }

    val containerColor = when {
        isChannel -> Color(0xFF1A84F5)
        isGroup -> EmeraldMid
        else -> MintLight
    }

    val ringBrush = remember(storyRingSeen) {
        if (storyRingSeen) {
            Brush.linearGradient(listOf(Color(0xFF9EACA6), Color(0xFFB8C4BF)))
        } else {
            Brush.sweepGradient(
                listOf(EmeraldVibrant, EmeraldBright, GoldCrown.copy(alpha = 0.85f), EmeraldMid, EmeraldVibrant)
            )
        }
    }
    val innerSize = if (hasStoryRing) (size - 6.dp).coerceAtLeast(24.dp) else size

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        if (hasStoryRing) {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .border(2.2.dp, ringBrush, CircleShape)
                    .padding(2.5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
            )
        }

        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(CircleShape)
                .background(containerColor)
                .then(
                    if (borderColor != null) Modifier.border(2.5.dp, borderColor, CircleShape)
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            if (decodedBytes != null) {
                AsyncImage(
                    model = decodedBytes,
                    contentDescription = "$name avatar",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(innerSize)
                        .clip(CircleShape)
                )
            } else if (isChannel) {
                Icon(
                    imageVector = Icons.Default.Campaign,
                    contentDescription = "$name channel",
                    tint = Color.White,
                    modifier = Modifier.size(innerSize * 0.52f)
                )
            } else if (isGroup) {
                Icon(
                    imageVector = Icons.Default.Group,
                    contentDescription = "$name group",
                    tint = Color.White,
                    modifier = Modifier.size(innerSize * 0.52f)
                )
            } else {
                Text(
                    text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "N",
                    style = if (innerSize >= 80.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                    color = EmeraldDeep,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (showOnlineDot) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.26f)
                    .clip(CircleShape)
                    .background(EmeraldVibrant)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }
    }
}

@Composable
fun GoPremiumBanner(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("go_premium_banner"),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        shadowElevation = 4.dp
    ) {
        Box(
            modifier = Modifier
                .background(
                    brush = Brush.horizontalGradient(
                        listOf(Color(0xFF043D26), EmeraldDark, EmeraldMid)
                    )
                )
                .padding(horizontal = 16.dp, vertical = 13.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(GoldCrown.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.WorkspacePremium,
                            contentDescription = "Premium Crown",
                            tint = GoldCrown,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Go Premium",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Smart AI replies & cloud sync active",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.84f)
                        )
                    }
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "View premium features",
                    tint = Color.White.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
fun NomiBottomBar(
    selectedTab: MainBottomTab,
    totalUnreadCount: Int = 0,
    onSelectTab: (MainBottomTab) -> Unit,
    onPlusClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 10.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(66.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomNavItem(
                label = "Chats",
                selected = selectedTab == MainBottomTab.CHATS,
                selectedIcon = Icons.AutoMirrored.Filled.Chat,
                unselectedIcon = Icons.Outlined.ChatBubbleOutline,
                badgeCount = totalUnreadCount,
                testTag = "bottom_nav_chats",
                onClick = { onSelectTab(MainBottomTab.CHATS) }
            )
            BottomNavItem(
                label = "Groups",
                selected = selectedTab == MainBottomTab.GROUPS,
                selectedIcon = Icons.Default.Group,
                unselectedIcon = Icons.Outlined.Group,
                testTag = "bottom_nav_groups",
                onClick = { onSelectTab(MainBottomTab.GROUPS) }
            )

            // Center '+' action button positioned lower inside the bottom bar
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .padding(top = 6.dp)
                    .size(44.dp)
                    .shadow(5.dp, CircleShape, ambientColor = LoginGreenStart, spotColor = LoginGreenStart)
                    .clip(CircleShape)
                    .background(LoginGreenStart)
                    .clickable(onClick = onPlusClick)
                    .testTag("bottom_nav_plus"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Post 24-hour story",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }

            BottomNavItem(
                label = "Calls",
                selected = selectedTab == MainBottomTab.CALLS,
                selectedIcon = Icons.Default.Call,
                unselectedIcon = Icons.Outlined.Call,
                testTag = "bottom_nav_calls",
                onClick = { onSelectTab(MainBottomTab.CALLS) }
            )
            BottomNavItem(
                label = "Settings",
                selected = selectedTab == MainBottomTab.SETTINGS,
                selectedIcon = Icons.Default.Settings,
                unselectedIcon = Icons.Outlined.Settings,
                testTag = "bottom_nav_settings",
                onClick = { onSelectTab(MainBottomTab.SETTINGS) }
            )
        }
    }
}

@Composable
private fun BottomNavItem(
    label: String,
    selected: Boolean,
    selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    badgeCount: Int = 0,
    testTag: String,
    onClick: () -> Unit
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box {
            Icon(
                imageVector = if (selected) selectedIcon else unselectedIcon,
                contentDescription = label,
                tint = if (selected) activeColor else inactiveColor,
                modifier = Modifier.size(22.dp)
            )
            if (badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-4).dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(EmeraldBright)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                        .testTag("bottom_nav_unread_badge"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) activeColor else inactiveColor,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
fun SmallFloatingPopup(
    message: String?,
    modifier: Modifier = Modifier
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = !message.isNullOrBlank(),
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 2 },
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it / 2 },
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .testTag("small_floating_popup"),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF1B2420),
            shadowElevation = 8.dp
        ) {
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}

