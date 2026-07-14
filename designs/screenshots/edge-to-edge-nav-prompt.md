# Gemini prompt — Jetpack Compose bottom nav not flush to screen bottom on MIUI/HyperOS

You are an expert Android / Jetpack Compose engineer. I have a bug I cannot crack and I want
a precise root-cause diagnosis plus the exact code fix. Please be concrete and specific to my
setup — do not give generic edge-to-edge boilerplate I've already tried (listed below).

## Goal
My app has a custom bottom navigation bar (a `Column` used as `Scaffold`'s `bottomBar`). I want
its background to extend **all the way to the very bottom edge of the screen**, drawing *behind*
the gesture-navigation "home pill", so the nav bar sits flush at the bottom with no gap or
foreign-colored strip beneath it. (Full-bleed / true edge-to-edge at the bottom.)

## Environment
- Device: Redmi Note 12 Pro (model 22101316UCP)
- OS: **HyperOS / MIUI (V816)**, **Android 14, API level 34**
- Navigation: **gesture navigation** (settings `navigation_mode = 2`), home-pill only
- App: `compileSdk 36`, `targetSdk 36`, `minSdk 24`
- Jetpack: `androidx.activity:activity-compose:1.10.1`, Compose BOM `2024.09.00`,
  Kotlin 2.2.10, AGP 9.1.1, Material3
- Activity theme parent: `android:Theme.DeviceDefault.NoActionBar`

## Precise symptom (measured from a device screenshot, 1080 x 2400 px)
I sampled pixel colors down a vertical line near the left edge (avoiding icons and the center pill):

- **Top edge:** at `y = 0`, the pixel is my app surface color `#FCFBFF (252,251,255)`. So my app
  content DOES draw up to the very top, behind the status bar. **Edge-to-edge works at the top.**
- **Bottom edge:** my nav bar background `#F2EFF7 (242,239,247)` runs down to `y = 2355`, then there
  is a HARD cut to an opaque near-white `(254,253,254)` from `y = 2356` to `y = 2400` (~45px / ~15dp).
  That bottom ~45px strip is NOT any color in my app — it looks like the **system navigation bar
  background painted opaque over my content**, ignoring my transparent `navigationBarColor`.

So the failure is ONLY at the bottom nav bar, not the top. The top proves `decorFitsSystemWindows`
is false and the window is laid out edge-to-edge; the bottom still shows a foreign opaque strip.

## Relevant code

### MainActivity.onCreate
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()                 // androidx.activity.enableEdgeToEdge
    ...
    setContent { YapTheme { MainScreen(...) } }
}
```

### themes.xml
```xml
<style name="Theme.Yap" parent="android:Theme.DeviceDefault.NoActionBar">
    <item name="android:statusBarColor">@android:color/transparent</item>
    <item name="android:navigationBarColor">@android:color/transparent</item>
</style>
```

### Scaffold + custom bottomBar (Material3)
```kotlin
Scaffold(
    modifier = Modifier.fillMaxSize(),
    contentWindowInsets = WindowInsets(0, 0, 0, 0),   // I zero these and handle insets myself
    bottomBar = {
        if (currentRoute in listOf(CAPTURE, LIBRARY, SETTINGS)) {
            // Intent: background reaches the screen bottom edge; items sit above the gesture
            // inset via the trailing spacer, and the bg fills behind the home pill.
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically,
                ) { /* 3 nav items */ }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
) { innerPadding ->
    NavHost(
        ...,
        modifier = Modifier.padding(innerPadding).statusBarsPadding()
    ) { /* screens */ }
}
```

## What I have already tried (none fixed the opaque bottom strip)
1. `enableEdgeToEdge()` in `onCreate` before `setContent`.
2. `android:statusBarColor` and `android:navigationBarColor` set to `@android:color/transparent`
   in the activity theme.
3. Custom `bottomBar` = `Column(background = surfaceContainer) { Row(72dp) + Spacer(
   windowInsetsBottomHeight(WindowInsets.navigationBars)) }` so the background should extend under
   the pill while the items stay above the inset.
4. `Scaffold(contentWindowInsets = WindowInsets(0,0,0,0))` + `Modifier.statusBarsPadding()` on the
   NavHost content, so the Scaffold does not double-inset the bottom bar.

The top edge went truly edge-to-edge after these; the **bottom nav-bar area still shows the ~45px
opaque near-white strip** over my content.

## Questions
1. What is the exact root cause of the opaque ~45px strip at the bottom on this MIUI/HyperOS +
   Android 14 + gesture-nav setup, given the top is already edge-to-edge? Is this
   `isNavigationBarContrastEnforced`, a MIUI-forced opaque navigation bar background, a
   `SystemBarStyle` scrim from `enableEdgeToEdge()`, or something else?
2. Give me the **precise Kotlin/XML changes** to make the app draw its own content fully behind the
   gesture nav bar so my `surfaceContainer` background reaches `y = 2400` (screen bottom). Include
   whether I should:
   - call `enableEdgeToEdge(navigationBarStyle = SystemBarStyle.dark/light/auto(Color.TRANSPARENT))`
     with specific args,
   - set `window.isNavigationBarContrastEnforced = false` (and where/when),
   - set `WindowCompat.setDecorFitsSystemWindows(window, false)` explicitly,
   - change the theme parent (e.g. to a Material3 / `Theme.Material3.*` theme) or add specific theme
     items, and whether `android:enforceNavigationBarContrast` / `android:windowLayoutInDisplayCutoutMode`
     matter here,
   - or anything MIUI/HyperOS-specific.
3. Confirm whether my `contentWindowInsets = WindowInsets(0)` + `statusBarsPadding()` approach is
   correct, or if it fights the fix.
4. Is there a reliable way to verify at runtime that the window is truly drawing behind the nav bar
   (e.g. reading `WindowInsets` / `RootWindowInsets`) so I can confirm the fix without eyeballing?

Please give the minimal, correct, production-grade fix with exact code, and explain why it works
where my four attempts did not.
