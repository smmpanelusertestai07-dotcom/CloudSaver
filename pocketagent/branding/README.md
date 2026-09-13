# PocketAgent brand assets

`play-store-icon.png` is the approved cobalt code-brackets + pearl AI spark icon,
with a full-square charcoal background. It is retained verbatim as the Play listing
asset: 512 × 512 px, 32-bit RGBA PNG, sRGB, below 1 MiB. The store supplies corner
masking and outer shadow. Do not add a second panel or text behind the mark.

`pocketagent-mark.svg` is the scalable geometric adaptation of that artwork, used
for the Android foreground, in-app mark, splash and Linux desktop icon. The SVG
and Android vectors share paths and gradient stops. Notification and themed icons
use the same silhouette in white alpha only, with no background.

Regenerate all derived resources with Node.js, the `sharp` package and ImageMagick installed:

```sh
node tools/make_brand_icons.mjs
node tests/brand-assets-test.mjs
```

Android asset rules applied:

| Asset | Canvas / visible area |
| --- | --- |
| Adaptive foreground and monochrome | 108 dp, mark inside the central 66 dp circle |
| Adaptive background | Solid `#191A1B`, no baked corners |
| Legacy launcher | 48, 72, 96, 144, 192 px; square and round variants |
| Notification small icon | 24 dp; white alpha only, approximately 20 dp wide |
| Android 12+ splash | 288 dp; mark inside the central 192 dp circle, no icon plate |
| In-app / Linux reusable image | Transparent 512 px PNG plus resolution-independent vector |
| Linux wallpaper | 1600 × 1600 px, with PocketAgent mark and caption; edges match desktop background |

References: [Android adaptive icons](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive),
[splash screens](https://developer.android.com/develop/ui/views/launch/splash-screen),
[Google Play icon specifications](https://developer.android.com/distribute/google-play/resources/icon-design-specifications).

The Play listing icon and Android launcher foreground are different deliverables;
the full-square Play image must not be used as an adaptive foreground.
