package com.music.laya.wear

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.music.laya.wear.data.Recognizer

/** One button, the Laya mark: opens the app already listening for the song playing nearby. */
class RecognizeTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val open = ModifiersBuilders.Clickable.Builder()
            .setId("recognize")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(WearActivity::class.java.name)
                            .addKeyToExtraMapping(Recognizer.EXTRA_RECOGNIZE, ActionBuilders.booleanExtra(true))
                            .build(),
                    )
                    .build(),
            )
            .build()
        val button = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(64f))
            .setHeight(dp(64f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(open)
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(WHITE))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(32f)).build())
                            .build(),
                    )
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Image.Builder()
                    .setResourceId(LOGO)
                    .setWidth(dp(32f))
                    .setHeight(dp(32f))
                    .setColorFilter(LayoutElementBuilders.ColorFilter.Builder().setTint(argb(BLACK)).build())
                    .build(),
            )
            .build()
        val label = LayoutElementBuilders.Text.Builder()
            .setText(getString(R.string.recognize))
            .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setColor(argb(WHITE)).setSize(sp(14f)).build())
            .build()
        val layout = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(button)
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(10f)).build())
            .addContent(label)
            .build()
        return Futures.immediateFuture(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(VERSION)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
                .build(),
        )
    }

    @Deprecated("Superseded by onTileResourcesRequest's newer overloads; still what tiles 1.5 calls by default.")
    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(VERSION)
                .addIdToImageMapping(
                    LOGO,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(R.drawable.ic_notification).build(),
                        )
                        .build(),
                )
                .build(),
        )

    private companion object {
        const val VERSION = "1"
        const val LOGO = "logo"
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
