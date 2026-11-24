package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

val OutfitFontFamily = FontFamily(
    Font(R.font.outfit, FontWeight.Normal),
    Font(R.font.outfit, FontWeight.SemiBold),
    Font(R.font.outfit, FontWeight.Bold)
)

val PlusJakartaFontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold)
)

val NomiTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = PlusJakartaFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = PlusJakartaFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp
    ),
    labelLarge = TextStyle(
        fontFamily = PlusJakartaFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = PlusJakartaFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = PlusJakartaFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
)

val NotoNastaliqStoryFont = FontFamily(Font(R.font.noto_nastaliq_urdu, FontWeight.Normal))
val GulzarStoryFont = FontFamily(Font(R.font.gulzar, FontWeight.Normal))
val AmiriStoryFont = FontFamily(Font(R.font.amiri, FontWeight.Normal))
val ScheherazadeStoryFont = FontFamily(Font(R.font.scheherazade_new, FontWeight.Normal))
val LateefStoryFont = FontFamily(Font(R.font.lateef, FontWeight.Normal))
val CairoStoryFont = FontFamily(Font(R.font.cairo, FontWeight.Normal))
val VazirmatnStoryFont = FontFamily(Font(R.font.vazirmatn, FontWeight.Normal))
val IbmPlexStoryFont = FontFamily(Font(R.font.ibm_plex_sans_arabic, FontWeight.Medium))
val ElMessiriStoryFont = FontFamily(Font(R.font.el_messiri, FontWeight.Normal))
val MirzaStoryFont = FontFamily(Font(R.font.mirza, FontWeight.Bold))

fun com.example.data.model.StoryFontStyle.toFontFamily(): FontFamily {
    return when (this) {
        com.example.data.model.StoryFontStyle.NOTO_NASTALIQ -> NotoNastaliqStoryFont
        com.example.data.model.StoryFontStyle.GULZAR -> GulzarStoryFont
        com.example.data.model.StoryFontStyle.AMIRI -> AmiriStoryFont
        com.example.data.model.StoryFontStyle.SCHEHERAZADE -> ScheherazadeStoryFont
        com.example.data.model.StoryFontStyle.LATEEF -> LateefStoryFont
        com.example.data.model.StoryFontStyle.CAIRO -> CairoStoryFont
        com.example.data.model.StoryFontStyle.VAZIRMATN -> VazirmatnStoryFont
        com.example.data.model.StoryFontStyle.IBM_PLEX -> IbmPlexStoryFont
        com.example.data.model.StoryFontStyle.EL_MESSIRI -> ElMessiriStoryFont
        com.example.data.model.StoryFontStyle.MIRZA -> MirzaStoryFont
    }
}
