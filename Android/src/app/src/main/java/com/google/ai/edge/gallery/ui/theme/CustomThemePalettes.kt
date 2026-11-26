package com.google.ai.edge.gallery.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

enum class CustomThemeType(val id: String, val label: String) {
  SYSTEM("system", "System"),
  LIGHT("light", "Light"),
  CATPPUCCIN("catppuccin", "Catppuccin"),
  DRACULA("dracula", "Dracula"),
  PTYXIS("ptyxis", "Ptyxis"),
  PTYXIS_EXTENDED("ptyxis_extended", "Ptyxis+");

  companion object {
    fun fromId(id: String?): CustomThemeType = entries.firstOrNull { it.id == id } ?: SYSTEM
  }
}

enum class CatppuccinFlavor(val displayName: String, val id: String) {
  LATTE("Latte", "latte"),
  FRAPPE("Frappé", "frappe"),
  MACCHIATO("Macchiato", "macchiato"),
  MOCHA("Mocha", "mocha");

  companion object {
    fun fromId(id: String?): CatppuccinFlavor = entries.firstOrNull { it.id == id } ?: MOCHA
  }
}

enum class CatppuccinAccent(val id: String, val displayName: String) {
  ROSEWATER("rosewater", "Rosewater"),
  FLAMINGO("flamingo", "Flamingo"),
  PINK("pink", "Pink"),
  MAUVE("mauve", "Mauve"),
  RED("red", "Red"),
  MAROON("maroon", "Maroon"),
  PEACH("peach", "Peach"),
  YELLOW("yellow", "Yellow"),
  GREEN("green", "Green"),
  TEAL("teal", "Teal"),
  SKY("sky", "Sky"),
  SAPPHIRE("sapphire", "Sapphire"),
  BLUE("blue", "Blue"),
  LAVENDER("lavender", "Lavender");

  fun secondaryCompanion(): CatppuccinAccent = when (this) {
    ROSEWATER -> FLAMINGO
    FLAMINGO -> PINK
    PINK -> MAUVE
    MAUVE -> LAVENDER
    RED -> MAROON
    MAROON -> RED
    PEACH -> YELLOW
    YELLOW -> PEACH
    GREEN -> TEAL
    TEAL -> SKY
    SKY -> SAPPHIRE
    SAPPHIRE -> BLUE
    BLUE -> LAVENDER
    LAVENDER -> BLUE
  }

  fun tertiaryCompanion(): CatppuccinAccent = when (this) {
    ROSEWATER -> PINK
    FLAMINGO -> MAUVE
    PINK -> LAVENDER
    MAUVE -> BLUE
    RED -> PEACH
    MAROON -> PEACH
    PEACH -> GREEN
    YELLOW -> GREEN
    GREEN -> SKY
    TEAL -> GREEN
    SKY -> TEAL
    SAPPHIRE -> SKY
    BLUE -> SAPPHIRE
    LAVENDER -> MAUVE
  }

  fun colorFor(flavor: CatppuccinFlavor): Color = when (flavor) {
    CatppuccinFlavor.LATTE -> when (this) {
      ROSEWATER -> Color(0xFFDC8A78)
      FLAMINGO -> Color(0xFFDD7878)
      PINK -> Color(0xFFEA76CB)
      MAUVE -> Color(0xFF8839EF)
      RED -> Color(0xFFD20F39)
      MAROON -> Color(0xFFE64553)
      PEACH -> Color(0xFFFE640B)
      YELLOW -> Color(0xFFDF8E1D)
      GREEN -> Color(0xFF40A02B)
      TEAL -> Color(0xFF179299)
      SKY -> Color(0xFF04A5E5)
      SAPPHIRE -> Color(0xFF209FB5)
      BLUE -> Color(0xFF1E66F5)
      LAVENDER -> Color(0xFF7287FD)
    }
    CatppuccinFlavor.FRAPPE -> when (this) {
      ROSEWATER -> Color(0xFFF2D5CF)
      FLAMINGO -> Color(0xFFEEBEBE)
      PINK -> Color(0xFFF4B8E4)
      MAUVE -> Color(0xFFCA9EE6)
      RED -> Color(0xFFE78284)
      MAROON -> Color(0xFFEA999C)
      PEACH -> Color(0xFFEF9F76)
      YELLOW -> Color(0xFFE5C890)
      GREEN -> Color(0xFFA6D189)
      TEAL -> Color(0xFF81C8BE)
      SKY -> Color(0xFF99D1DB)
      SAPPHIRE -> Color(0xFF85C1DC)
      BLUE -> Color(0xFF8CAAEE)
      LAVENDER -> Color(0xFFBABBF1)
    }
    CatppuccinFlavor.MACCHIATO -> when (this) {
      ROSEWATER -> Color(0xFFF4DBD6)
      FLAMINGO -> Color(0xFFF0C6C6)
      PINK -> Color(0xFFF5BDE6)
      MAUVE -> Color(0xFFC6A0F6)
      RED -> Color(0xFFED8796)
      MAROON -> Color(0xFFEE99A0)
      PEACH -> Color(0xFFF5A97F)
      YELLOW -> Color(0xFFEED49F)
      GREEN -> Color(0xFFA6DA95)
      TEAL -> Color(0xFF8BD5CA)
      SKY -> Color(0xFF91D7E3)
      SAPPHIRE -> Color(0xFF7DC4E4)
      BLUE -> Color(0xFF8AADF4)
      LAVENDER -> Color(0xFFB7BDF8)
    }
    CatppuccinFlavor.MOCHA -> when (this) {
      ROSEWATER -> Color(0xFFF5E0DC)
      FLAMINGO -> Color(0xFFF2CDCD)
      PINK -> Color(0xFFF5C2E7)
      MAUVE -> Color(0xFFCBA6F7)
      RED -> Color(0xFFF38BA8)
      MAROON -> Color(0xFFEBA0AC)
      PEACH -> Color(0xFFFAB387)
      YELLOW -> Color(0xFFF9E2AF)
      GREEN -> Color(0xFFA6E3A1)
      TEAL -> Color(0xFF94E2D5)
      SKY -> Color(0xFF89DCEB)
      SAPPHIRE -> Color(0xFF74C7EC)
      BLUE -> Color(0xFF89B4FA)
      LAVENDER -> Color(0xFFB4BEFE)
    }
  }

  companion object {
    fun fromId(id: String?): CatppuccinAccent = entries.firstOrNull { it.id == id } ?: MAUVE
  }
}

enum class DraculaAccent(val id: String, val displayName: String, val darkColor: Color, val lightColor: Color) {
  PURPLE("purple", "Purple", Color(0xFFBD93F9), Color(0xFF7B4FEF)),
  PINK("pink", "Pink", Color(0xFFFF79C6), Color(0xFFD9337A)),
  CYAN("cyan", "Cyan", Color(0xFF8BE9FD), Color(0xFF0090C0)),
  GREEN("green", "Green", Color(0xFF50FA7B), Color(0xFF1C7A3D)),
  ORANGE("orange", "Orange", Color(0xFFFFB86C), Color(0xFFC55A00)),
  RED("red", "Red", Color(0xFFFF5555), Color(0xFFCC2222)),
  YELLOW("yellow", "Yellow", Color(0xFFF1FA8C), Color(0xFFA07800));

  fun secondaryCompanion(): DraculaAccent = when (this) {
    PURPLE -> PINK
    PINK -> PURPLE
    CYAN -> GREEN
    GREEN -> CYAN
    ORANGE -> YELLOW
    RED -> ORANGE
    YELLOW -> ORANGE
  }

  fun tertiaryCompanion(): DraculaAccent = when (this) {
    PURPLE -> CYAN
    PINK -> ORANGE
    CYAN -> PURPLE
    GREEN -> YELLOW
    ORANGE -> RED
    RED -> PINK
    YELLOW -> GREEN
  }

  companion object {
    fun fromId(id: String?): DraculaAccent = entries.firstOrNull { it.id == id } ?: PURPLE
  }
}

enum class PtyxisPalette(
  val id: String,
  val displayName: String,
  val bg: Color,
  val fg: Color,
  val accent1: Color,
  val accent2: Color,
  val accent3: Color,
  val error: Color,
) {
  FAIRY_FLOSS("fairy_floss", "Fairy Floss", Color(0xFF5A5475), Color(0xFFC2FFDF), Color(0xFFFFB8D1), Color(0xFFAE81FF), Color(0xFFC2FFDF), Color(0xFFFF857F)),
  NORD("nord", "Nord", Color(0xFF2E3440), Color(0xFFD8DEE9), Color(0xFF88C0D0), Color(0xFF81A1C1), Color(0xFF8FBCBB), Color(0xFFBF616A)),
  BIM("bim", "Bim", Color(0xFF012849), Color(0xFFA9BED8), Color(0xFF5EA2EC), Color(0xFFF557A0), Color(0xFFA9EE55), Color(0xFFF557A0)),
  BORLAND("borland", "Borland", Color(0xFF0000A4), Color(0xFFFFFF4E), Color(0xFFFFFF4E), Color(0xFFFF73FD), Color(0xFF96CBFE), Color(0xFFFF6C60)),
  C64("c64", "C64", Color(0xFF40318D), Color(0xFF7869C4), Color(0xFF67B6BD), Color(0xFFBFCE72), Color(0xFF8B3F96), Color(0xFF883932)),
  COBALT_NEON("cobalt_neon", "Cobalt Neon", Color(0xFF142838), Color(0xFF8FF586), Color(0xFF8FF586), Color(0xFF3BA5FF), Color(0xFFE9E75C), Color(0xFFFF2320)),
  GRASS("grass", "Grass", Color(0xFF13773D), Color(0xFFFFF0A5), Color(0xFFE7B000), Color(0xFF00BBBB), Color(0xFFFFF0A5), Color(0xFFBB0000)),
  HOMEBREW_OCEAN("homebrew_ocean", "Homebrew Ocean", Color(0xFF224FBC), Color(0xFFFFFFFF), Color(0xFF00A6B2), Color(0xFF00A600), Color(0xFF999900), Color(0xFF990000)),
  MONO_AMBER("mono_amber", "Mono Amber", Color(0xFF2B1900), Color(0xFFFF9400), Color(0xFFFF9400), Color(0xFFFF9400), Color(0xFFFF9400), Color(0xFFFF9400)),
  MONO_RED("mono_red", "Mono Red", Color(0xFF2B0C00), Color(0xFFFF3600), Color(0xFFFF3600), Color(0xFFFF3600), Color(0xFFFF3600), Color(0xFFFF3600)),
  SYNTHWAVE("synthwave", "Synthwave", Color(0xFF262335), Color(0xFFFFFFFF), Color(0xFFFF7EDB), Color(0xFF03EDF9), Color(0xFFFEDE5D), Color(0xFFFE4450));

  companion object {
    fun fromId(id: String?): PtyxisPalette = entries.firstOrNull { it.id == id } ?: NORD
  }
}

enum class PtyxisExtendedPalette(
  val id: String,
  val displayName: String,
  val bg: Color,
  val fg: Color,
  val error: Color,
  val primary: Color,
  val secondary: Color,
  val tertiary: Color,
) {
  ACI("aci", "Aci", Color(0xFF0D1926), Color(0xFFB4E1FD), Color(0xFFFF0883), Color(0xFF0883FF), Color(0xFF8308FF), Color(0xFF08FF83)),
  AFTERGLOW("afterglow", "Afterglow", Color(0xFF222222), Color(0xFFD0D0D0), Color(0xFFA53C23), Color(0xFF6C99BB), Color(0xFF9F4E85), Color(0xFF7DD6CF)),
  ARGONAUT("argonaut", "Argonaut", Color(0xFF0E1019), Color(0xFFFFFAF4), Color(0xFFFF000F), Color(0xFF008DF8), Color(0xFF6D43A6), Color(0xFF00D8EB)),
  AURA("aura", "Aura", Color(0xFF15141B), Color(0xFFEDECEE), Color(0xFFFF6767), Color(0xFFA277FF), Color(0xFFA277FF), Color(0xFF61FFCA)),
  AYU_MIRAGE("ayu_mirage", "Ayu Mirage", Color(0xFF1F2430), Color(0xFFCBCCC6), Color(0xFFFF3333), Color(0xFF73D0FF), Color(0xFFD4BFFF), Color(0xFF95E6CB)),
  BELAFONTE("belafonte", "Belafonte", Color(0xFF20111B), Color(0xFF968C83), Color(0xFFBE100E), Color(0xFF426A79), Color(0xFF97522C), Color(0xFF989A9C)),
  BELAFONTE_DAY("belafonte_day", "Belafonte Day", Color(0xFFD5CCBA), Color(0xFF45373C), Color(0xFFBE100E), Color(0xFF426A79), Color(0xFF97522C), Color(0xFF989A9C)),
  BIRDS_OF_PARADISE("birds_of_paradise", "Birds Of Paradise", Color(0xFF2A1F1D), Color(0xFFE0DBB7), Color(0xFFBE2D26), Color(0xFF5A86AD), Color(0xFFAC80A6), Color(0xFF74A6AD)),
  BLAZER("blazer", "Blazer", Color(0xFF0D1926), Color(0xFFD9E6F2), Color(0xFFB87A7A), Color(0xFF7A7AB8), Color(0xFFB87AB8), Color(0xFF7AB8B8)),
  BROGRAMMER("brogrammer", "Brogrammer", Color(0xFF131313), Color(0xFFD6DBE5), Color(0xFFF81118), Color(0xFF2A84D2), Color(0xFF4E5AB7), Color(0xFF1081D6)),
  CHALKBOARD("chalkboard", "Chalkboard", Color(0xFF29262F), Color(0xFFD9E6F2), Color(0xFFC37372), Color(0xFF7372C3), Color(0xFFC372C2), Color(0xFF72C2C3)),
  DEUTERANOPIA("deuteranopia", "Deuteranopia", Color(0xFF15171A), Color(0xFFE6E9EC), Color(0xFFD55E00), Color(0xFF648FFF), Color(0xFFCC79A7), Color(0xFF009988)),
  ESPRESSO_LIBRE("espresso_libre", "Espresso Libre", Color(0xFF2A211C), Color(0xFFB8A898), Color(0xFFCC0000), Color(0xFF0066FF), Color(0xFFC5656B), Color(0xFF06989A)),
  EVERFOREST("everforest", "Everforest", Color(0xFF2D353B), Color(0xFFD3C6AA), Color(0xFFE67E80), Color(0xFF7FBBB3), Color(0xFFD699B6), Color(0xFF83C092)),
  EVERFOREST_LIGHT("everforest_light", "Everforest Light", Color(0xFFFDF6E3), Color(0xFF5C6A72), Color(0xFFF85552), Color(0xFF3A94C5), Color(0xFFDF69BA), Color(0xFF35A77C)),
  FLATLAND("flatland", "Flatland", Color(0xFF1D1F21), Color(0xFFB8DBEF), Color(0xFFF18339), Color(0xFF5096BE), Color(0xFF695ABC), Color(0xFFD63865)),
  GITHUB("github", "GitHub", Color(0xFF101216), Color(0xFF8B949E), Color(0xFFF78166), Color(0xFF6CA4F8), Color(0xFFDB61A2), Color(0xFF2B7489)),
  GITHUB_LIGHT("github_light", "GitHub Light", Color(0xFFF4F4F4), Color(0xFF3E3E3E), Color(0xFF970B16), Color(0xFF003E8A), Color(0xFFE94691), Color(0xFF89D1EC)),
  IBM3270("ibm3270", "IBM3270", Color(0xFF000000), Color(0xFFFDFDFD), Color(0xFFF01818), Color(0xFF7890F0), Color(0xFFF078D8), Color(0xFF54E4E4)),
  IC_GREEN_PPL("ic_green_ppl", "IC Green PPL", Color(0xFF3A3D3F), Color(0xFFD9EFD3), Color(0xFFFB002A), Color(0xFF149B45), Color(0xFF53B82C), Color(0xFF2CB868)),
  KANAGAWA("kanagawa", "Kanagawa", Color(0xFF1F1F28), Color(0xFFDCD7BA), Color(0xFFC34043), Color(0xFF7E9CD8), Color(0xFF957FB8), Color(0xFF6A9589)),
  MATERIAL("material", "Material", Color(0xFF1E282C), Color(0xFFC3C7D1), Color(0xFFEB606B), Color(0xFF80CBC3), Color(0xFFFF2490), Color(0xFFAEDDFF)),
  MONA_LISA("mona_lisa", "Mona Lisa", Color(0xFF120B0D), Color(0xFFF7D66A), Color(0xFF9B291C), Color(0xFF515C5D), Color(0xFF9B1D29), Color(0xFF588056)),
  MONO_CYAN("mono_cyan", "Mono Cyan", Color(0xFF00222B), Color(0xFF00CCFF), Color(0xFF00CCFF), Color(0xFF00CCFF), Color(0xFF00CCFF), Color(0xFF00CCFF)),
  MONOKAI_PRO("monokai_pro", "Monokai Pro", Color(0xFF363537), Color(0xFFFDF9F3), Color(0xFFFF6188), Color(0xFFFC9867), Color(0xFFAB9DF2), Color(0xFF78DCE8)),
  OMNI("omni", "Omni", Color(0xFF191622), Color(0xFFABB2BF), Color(0xFFE96379), Color(0xFF78D1E1), Color(0xFF988BC7), Color(0xFFFF79C6)),
  PARAISO_DARK("paraiso_dark", "Paraiso Dark", Color(0xFF2F1E2E), Color(0xFFA39E9B), Color(0xFFEF6155), Color(0xFF06B6EF), Color(0xFF815BA4), Color(0xFF5BC4BF)),
  PIXIEFLOSS("pixiefloss", "Pixiefloss", Color(0xFF241F33), Color(0xFFD1CAE8), Color(0xFFFF857F), Color(0xFFAE81FF), Color(0xFFEF6155), Color(0xFFC2FFDF)),
  POWERSHELL("powershell", "Powershell", Color(0xFF052454), Color(0xFFF6F6F7), Color(0xFF7E0008), Color(0xFF010083), Color(0xFFD33682), Color(0xFF0E807F)),
  PROTANOPIA("protanopia", "Protanopia", Color(0xFF15171A), Color(0xFFE6E9EC), Color(0xFFCC3311), Color(0xFF785EF0), Color(0xFFAA4499), Color(0xFF56B4E9)),
  RELAXED("relaxed", "Relaxed", Color(0xFF353A44), Color(0xFFD9D9D9), Color(0xFFBC5653), Color(0xFF6A8799), Color(0xFFB06698), Color(0xFFC9DFFF)),
  SEA_SHELLS("sea_shells", "Sea Shells", Color(0xFF09141B), Color(0xFFDEB88D), Color(0xFFD15123), Color(0xFF1E4950), Color(0xFF68D4F1), Color(0xFF50A3B5)),
  SEAFOAM_PASTEL("seafoam_pastel", "Seafoam Pastel", Color(0xFF243435), Color(0xFFD4E7D4), Color(0xFF825D4D), Color(0xFF4D7B82), Color(0xFF8A7267), Color(0xFF729494)),
  SOLARIZED("solarized", "Solarized", Color(0xFF002B36), Color(0xFF839496), Color(0xFFDC322F), Color(0xFF268BD2), Color(0xFFD33682), Color(0xFF2AA198)),
  SOLARIZED_LIGHT("solarized_light", "Solarized Light", Color(0xFFFDF6E3), Color(0xFF657B83), Color(0xFFDC322F), Color(0xFF268BD2), Color(0xFFD33682), Color(0xFF2AA198)),
  SPACEDUST("spacedust", "Spacedust", Color(0xFF0A1E24), Color(0xFFECF0C1), Color(0xFFE35B00), Color(0xFF0F548B), Color(0xFFE35B00), Color(0xFF06AFC7)),
  SPRING("spring", "Spring", Color(0xFF0A1E24), Color(0xFFECF0C1), Color(0xFFFF4D83), Color(0xFF1DD3EE), Color(0xFF8959A8), Color(0xFF3E999F)),
  TWILIGHT("twilight", "Twilight", Color(0xFF141414), Color(0xFFFFFFD4), Color(0xFFC06D44), Color(0xFF44474A), Color(0xFFB4BE7C), Color(0xFF778385)),
  TRITANOPIA("tritanopia", "Tritanopia", Color(0xFF15171A), Color(0xFFE6E9EC), Color(0xFFD55E00), Color(0xFF648FFF), Color(0xFFAA4499), Color(0xFF66CCEE)),
  URPLE("urple", "Urple", Color(0xFF1B1B23), Color(0xFF877A9B), Color(0xFFB0425B), Color(0xFF564D9B), Color(0xFF6C3CA1), Color(0xFF808080)),
  XTERM("xterm", "Xterm", Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFFCD0000), Color(0xFF0000EE), Color(0xFFCD00CD), Color(0xFF00CDCD)),
  XTERM_LIGHT("xterm_light", "Xterm Light", Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFFCD0000), Color(0xFF0000EE), Color(0xFFCD00CD), Color(0xFF00CDCD));

  companion object {
    fun fromId(id: String?): PtyxisExtendedPalette = entries.firstOrNull { it.id == id } ?: SOLARIZED
  }
}

private data class PaletteBaseColors(
  val text: Color,
  val subtext1: Color,
  val overlay1: Color,
  val overlay0: Color,
  val surface2: Color,
  val surface1: Color,
  val surface0: Color,
  val base: Color,
  val mantle: Color,
  val crust: Color,
)

private fun catppuccinBase(flavor: CatppuccinFlavor): PaletteBaseColors = when (flavor) {
  CatppuccinFlavor.LATTE -> PaletteBaseColors(
    text = Color(0xFF4C4F69), subtext1 = Color(0xFF5C5F77), overlay1 = Color(0xFF8C8FA1), overlay0 = Color(0xFF9CA0B0),
    surface2 = Color(0xFFACB0BE), surface1 = Color(0xFFBCC0CC), surface0 = Color(0xFFCCD0DA),
    base = Color(0xFFEFF1F5), mantle = Color(0xFFE6E9EF), crust = Color(0xFFDCE0E8),
  )
  CatppuccinFlavor.FRAPPE -> PaletteBaseColors(
    text = Color(0xFFC6D0F5), subtext1 = Color(0xFFB5BFE2), overlay1 = Color(0xFF838BA7), overlay0 = Color(0xFF737994),
    surface2 = Color(0xFF626880), surface1 = Color(0xFF51576D), surface0 = Color(0xFF414559),
    base = Color(0xFF303446), mantle = Color(0xFF292C3C), crust = Color(0xFF232634),
  )
  CatppuccinFlavor.MACCHIATO -> PaletteBaseColors(
    text = Color(0xFFCAD3F5), subtext1 = Color(0xFFB8C0E0), overlay1 = Color(0xFF8087A2), overlay0 = Color(0xFF6E738D),
    surface2 = Color(0xFF5B6078), surface1 = Color(0xFF494D64), surface0 = Color(0xFF363A4F),
    base = Color(0xFF24273A), mantle = Color(0xFF1E2030), crust = Color(0xFF181926),
  )
  CatppuccinFlavor.MOCHA -> PaletteBaseColors(
    text = Color(0xFFCDD6F4), subtext1 = Color(0xFFBAC2DE), overlay1 = Color(0xFF7F849C), overlay0 = Color(0xFF6C7086),
    surface2 = Color(0xFF585B70), surface1 = Color(0xFF45475A), surface0 = Color(0xFF313244),
    base = Color(0xFF1E1E2E), mantle = Color(0xFF181825), crust = Color(0xFF11111B),
  )
}

private val draculaDarkBase = PaletteBaseColors(
  text = Color(0xFFF8F8F2), subtext1 = Color(0xFFB8BAC8), overlay1 = Color(0xFF7B8DB0), overlay0 = Color(0xFF6272A4),
  surface2 = Color(0xFF565761), surface1 = Color(0xFF44475A), surface0 = Color(0xFF383A48),
  base = Color(0xFF282A36), mantle = Color(0xFF21222C), crust = Color(0xFF191A21),
)

private val draculaLightBase = PaletteBaseColors(
  text = Color(0xFF282A36), subtext1 = Color(0xFF414559), overlay1 = Color(0xFF6272A4), overlay0 = Color(0xFF7B8DB0),
  surface2 = Color(0xFFD6D8E7), surface1 = Color(0xFFE2E3ED), surface0 = Color(0xFFECEDFA),
  base = Color(0xFFF8F8F2), mantle = Color(0xFFF0F0F9), crust = Color(0xFFE8E8F5),
)

val boxLightColorScheme: ColorScheme = lightColorScheme(
  primary = Color(0xFF0B57D0),
  onPrimary = Color(0xFFFFFFFF),
  primaryContainer = Color(0xFFD3E3FD),
  onPrimaryContainer = Color(0xFF0842A0),
  inversePrimary = Color(0xFFA8C7FA),
  secondary = Color(0xFF00639B),
  onSecondary = Color(0xFFFFFFFF),
  secondaryContainer = Color(0xFFC2E7FF),
  onSecondaryContainer = Color(0xFF004A77),
  tertiary = Color(0xFF146C2E),
  onTertiary = Color(0xFFFFFFFF),
  tertiaryContainer = Color(0xFFC4EED0),
  onTertiaryContainer = Color(0xFF0F5223),
  background = Color(0xFFFFFFFF),
  onBackground = Color(0xFF1A1C1E),
  surface = Color(0xFFFFFFFF),
  onSurface = Color(0xFF1A1C1E),
  surfaceVariant = Color(0xFFE1E3E1),
  onSurfaceVariant = Color(0xFF444746),
  surfaceTint = Color(0xFF0B57D0),
  inverseSurface = Color(0xFF303030),
  inverseOnSurface = Color(0xFFF2F2F2),
  error = Color(0xFFB3261E),
  onError = Color(0xFFFFFFFF),
  errorContainer = Color(0xFFF9DEDC),
  onErrorContainer = Color(0xFF8C1D18),
  outline = Color(0xFF747775),
  outlineVariant = Color(0xFFC4C7C5),
  scrim = Color(0xFF000000),
  surfaceBright = Color(0xFFFFFFFF),
  surfaceDim = Color(0xFFDAD9DD),
  surfaceContainer = Color(0xFFF1F3F5),
  surfaceContainerHigh = Color(0xFFEBEDF0),
  surfaceContainerHighest = Color(0xFFE5E8EB),
  surfaceContainerLow = Color(0xFFF7F8FA),
  surfaceContainerLowest = Color(0xFFFFFFFF),
)

private fun buildCatppuccinOrDraculaScheme(
  isLight: Boolean,
  base: PaletteBaseColors,
  oppositeBase: PaletteBaseColors,
  primary: Color,
  secondary: Color,
  tertiary: Color,
  error: Color,
  inversePrimary: Color,
): ColorScheme {
  fun onAccent(c: Color): Color = if (c.luminance() < 0.35f) Color.White else base.text
  return if (isLight) lightColorScheme(
    primary,
    onAccent(primary),
    lerp(base.base, primary, 0.22f),
    lerp(base.text, primary, 0.2f),
    inversePrimary,
    secondary,
    onAccent(secondary),
    lerp(base.base, secondary, 0.22f),
    lerp(base.text, secondary, 0.2f),
    tertiary,
    onAccent(tertiary),
    lerp(base.base, tertiary, 0.22f),
    lerp(base.text, tertiary, 0.2f),
    base.base,
    base.text,
    base.base,
    base.text,
    base.surface0,
    base.subtext1,
    primary,
    oppositeBase.base,
    oppositeBase.text,
    error,
    onAccent(error),
    lerp(base.base, error, 0.22f),
    lerp(base.text, error, 0.2f),
    base.overlay1,
    base.surface2,
    base.crust,
    base.surface1,
    base.mantle,
    base.surface0,
    base.surface1,
    base.surface2,
    base.mantle,
    base.crust,
  ) else darkColorScheme(
    primary,
    onAccent(primary),
    lerp(base.base, primary, 0.22f),
    lerp(base.text, primary, 0.2f),
    inversePrimary,
    secondary,
    onAccent(secondary),
    lerp(base.base, secondary, 0.22f),
    lerp(base.text, secondary, 0.2f),
    tertiary,
    onAccent(tertiary),
    lerp(base.base, tertiary, 0.22f),
    lerp(base.text, tertiary, 0.2f),
    base.base,
    base.text,
    base.base,
    base.text,
    base.surface0,
    base.subtext1,
    primary,
    oppositeBase.base,
    oppositeBase.text,
    error,
    onAccent(error),
    lerp(base.base, error, 0.22f),
    lerp(base.text, error, 0.2f),
    base.overlay1,
    base.surface2,
    base.crust,
    base.surface1,
    base.mantle,
    base.surface0,
    base.surface1,
    base.surface2,
    base.mantle,
    base.crust,
  )
}

fun catppuccinColorScheme(
  flavor: CatppuccinFlavor,
  accent: CatppuccinAccent,
  monochrome: Boolean,
): ColorScheme {
  val isLight = flavor == CatppuccinFlavor.LATTE
  val base = catppuccinBase(flavor)
  val oppositeFlavor = if (isLight) CatppuccinFlavor.MOCHA else CatppuccinFlavor.LATTE
  val primary = accent.colorFor(flavor)
  val secondary = if (monochrome) primary else accent.secondaryCompanion().colorFor(flavor)
  val tertiary = if (monochrome) primary else accent.tertiaryCompanion().colorFor(flavor)
  val error = CatppuccinAccent.RED.colorFor(flavor)
  return buildCatppuccinOrDraculaScheme(
    isLight = isLight,
    base = base,
    oppositeBase = catppuccinBase(oppositeFlavor),
    primary = primary,
    secondary = secondary,
    tertiary = tertiary,
    error = error,
    inversePrimary = accent.colorFor(oppositeFlavor),
  )
}

fun draculaColorScheme(
  isDark: Boolean,
  accent: DraculaAccent,
  monochrome: Boolean,
): ColorScheme {
  val base = if (isDark) draculaDarkBase else draculaLightBase
  val oppositeBase = if (isDark) draculaLightBase else draculaDarkBase
  fun accentHex(a: DraculaAccent) = if (isDark) a.darkColor else a.lightColor
  fun oppositeAccentHex(a: DraculaAccent) = if (isDark) a.lightColor else a.darkColor
  val primary = accentHex(accent)
  val secondary = if (monochrome) primary else accentHex(accent.secondaryCompanion())
  val tertiary = if (monochrome) primary else accentHex(accent.tertiaryCompanion())
  val error = accentHex(DraculaAccent.RED)
  return buildCatppuccinOrDraculaScheme(
    isLight = !isDark,
    base = base,
    oppositeBase = oppositeBase,
    primary = primary,
    secondary = secondary,
    tertiary = tertiary,
    error = error,
    inversePrimary = oppositeAccentHex(accent),
  )
}

private fun contrastOn(color: Color): Color =
  if (color.luminance() >= 0.5f) Color(0xFF0A0A0A) else Color.White

private fun buildTerminalScheme(
  bg: Color,
  fg: Color,
  primary: Color,
  secondary: Color,
  tertiary: Color,
  error: Color,
  isLight: Boolean,
): ColorScheme {
  val surfaceBright = if (isLight) lerp(bg, Color.White, 0.14f) else lerp(bg, fg, 0.14f)
  val surfaceDim = if (isLight) lerp(bg, fg, 0.2f) else lerp(bg, Color.Black, 0.2f)
  val surfaceLowest = if (isLight) lerp(bg, Color.White, 0.4f) else lerp(bg, Color.Black, 0.2f)
  val surfaceLow = if (isLight) lerp(bg, Color.White, 0.2f) else lerp(bg, Color.Black, 0.4f)
  return if (isLight) lightColorScheme(
    primary,
    contrastOn(primary),
    lerp(bg, primary, 0.22f),
    lerp(fg, primary, 0.2f),
    primary,
    secondary,
    contrastOn(secondary),
    lerp(bg, secondary, 0.22f),
    lerp(fg, secondary, 0.2f),
    tertiary,
    contrastOn(tertiary),
    lerp(bg, tertiary, 0.22f),
    lerp(fg, tertiary, 0.2f),
    bg,
    fg,
    bg,
    fg,
    lerp(bg, fg, 0.12f),
    lerp(fg, bg, 0.25f),
    primary,
    fg,
    bg,
    error,
    contrastOn(error),
    lerp(bg, error, 0.22f),
    lerp(fg, error, 0.2f),
    lerp(bg, fg, 0.4f),
    lerp(bg, fg, 0.2f),
    Color.Black,
    surfaceBright,
    surfaceDim,
    lerp(bg, fg, 0.05f),
    lerp(bg, fg, 0.09f),
    lerp(bg, fg, 0.13f),
    surfaceLow,
    surfaceLowest,
  ) else darkColorScheme(
    primary,
    contrastOn(primary),
    lerp(bg, primary, 0.22f),
    lerp(fg, primary, 0.2f),
    primary,
    secondary,
    contrastOn(secondary),
    lerp(bg, secondary, 0.22f),
    lerp(fg, secondary, 0.2f),
    tertiary,
    contrastOn(tertiary),
    lerp(bg, tertiary, 0.22f),
    lerp(fg, tertiary, 0.2f),
    bg,
    fg,
    bg,
    fg,
    lerp(bg, fg, 0.12f),
    lerp(fg, bg, 0.25f),
    primary,
    fg,
    bg,
    error,
    contrastOn(error),
    lerp(bg, error, 0.22f),
    lerp(fg, error, 0.2f),
    lerp(bg, fg, 0.4f),
    lerp(bg, fg, 0.2f),
    Color.Black,
    surfaceBright,
    surfaceDim,
    lerp(bg, fg, 0.05f),
    lerp(bg, fg, 0.09f),
    lerp(bg, fg, 0.13f),
    surfaceLow,
    surfaceLowest,
  )
}

fun ptyxisColorScheme(palette: PtyxisPalette, monochrome: Boolean): ColorScheme =
  buildTerminalScheme(
    bg = palette.bg,
    fg = palette.fg,
    primary = palette.accent1,
    secondary = if (monochrome) palette.accent1 else palette.accent2,
    tertiary = if (monochrome) palette.accent1 else palette.accent3,
    error = palette.error,
    isLight = false,
  )

fun ptyxisExtendedColorScheme(palette: PtyxisExtendedPalette): ColorScheme {
  val isLight = palette.bg.luminance() > 0.5f
  return buildTerminalScheme(
    bg = palette.bg,
    fg = palette.fg,
    primary = palette.primary,
    secondary = palette.secondary,
    tertiary = palette.tertiary,
    error = palette.error,
    isLight = isLight,
  )
}
