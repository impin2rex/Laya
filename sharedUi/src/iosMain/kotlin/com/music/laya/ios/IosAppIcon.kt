package com.music.laya.ios

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.music.laya.sharedui.resources.Res
import com.music.laya.sharedui.resources.app_icon
import com.music.laya.sharedui.resources.app_icon_default
import com.music.laya.sharedui.resources.app_icon_liquid_glass
import com.music.laya.sharedui.resources.app_icon_liquid_glass_dark
import com.music.laya.sharedui.resources.ios_icon_default
import com.music.laya.sharedui.resources.ios_icon_liquid_glass
import com.music.laya.sharedui.resources.ios_icon_liquid_glass_dark
import com.music.laya.ui.screens.ROW_INSET
import com.music.laya.ui.screens.SettingsRow
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import platform.UIKit.UIApplication
import platform.UIKit.alternateIconName
import platform.UIKit.setAlternateIconName
import platform.UIKit.supportsAlternateIcons

/**
 * The home-screen icon choices, iOS's take on Android's AppIcon. [iconName] null is the primary
 * AppIcon in Assets.xcassets; the others are Icon Composer documents (AppIcon-LiquidGlass*.icon)
 * listed under ASSETCATALOG_COMPILER_ALTERNATE_APPICON_NAMES in project.yml. iOS 26 renders them as
 * real glass, and its Home Screen setting supplies clear and tinted looks of whichever is chosen;
 * Dark is its own icon so it stays dark in the light appearance. Older iOS gets a flat render.
 */
internal enum class IosAppIcon(val iconName: String?, val label: StringResource, val preview: DrawableResource) {
    Default(null, Res.string.app_icon_default, Res.drawable.ios_icon_default),
    LiquidGlass("AppIcon-LiquidGlass", Res.string.app_icon_liquid_glass, Res.drawable.ios_icon_liquid_glass),
    LiquidGlassDark("AppIcon-LiquidGlassDark", Res.string.app_icon_liquid_glass_dark, Res.drawable.ios_icon_liquid_glass_dark);

    companion object {
        val supported get() = UIApplication.sharedApplication.supportsAlternateIcons

        fun current(): IosAppIcon {
            val name = UIApplication.sharedApplication.alternateIconName
            return entries.firstOrNull { it.iconName == name } ?: Default
        }

        /** iOS shows its own "You have changed the icon" alert; [onDone] gets whether it stuck. */
        fun apply(icon: IosAppIcon, onDone: (Boolean) -> Unit) {
            UIApplication.sharedApplication.setAlternateIconName(icon.iconName) { error -> onDone(error == null) }
        }
    }
}

@Composable
internal fun IosAppIconPicker() {
    var selectedIcon by remember { mutableStateOf(IosAppIcon.current()) }
    SettingsRow(icon = Icons.Rounded.Apps, title = stringResource(Res.string.app_icon))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosAppIcon.entries.forEach { option ->
            val selected = option == selectedIcon
            Column(
                modifier = Modifier
                    .width(84.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        if (selected) return@clickable
                        val previous = selectedIcon
                        selectedIcon = option
                        IosAppIcon.apply(option) { ok -> if (!ok) selectedIcon = previous }
                    }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(option.preview),
                    contentDescription = null,
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(15.dp)),
                )
                Text(
                    text = stringResource(option.label),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
