# CloudSaver — Hinglish quick start

CloudSaver aapke phone par photos/videos ki chhoti (optimised) copies banata hai
aur unhe ek folder (`Pictures/CloudSaver`) mein rakhta hai. Aapka cloud app
(Ente, MEGA, Proton Drive, Filen, Nextcloud, Google Photos, Immich, Dropbox,
OneDrive ya koi bhi) sirf us folder ka backup leta hai. Aapke originals ko app
kabhi khud nahi hataata — hataane ka faisla hamesha aapka, Android ke apne
confirm dialog se.

Internet permission app ke paas hai hi nahi: kuch bhi phone se bahar nahi jaata.

## Install

1. GitHub Releases se `CloudSaver-vX.Y.Z-release.apk` download karo aur kholo.
2. Play Protect warning aaye to "More details" → "Install anyway".
3. Har naya version isi ke upar seedha install hoga (same signing key);
   uninstall karne ki zaroorat nahi.

## Pehli baar (setup ke 8 steps)

1. **Welcome** — Next.
2. **Photos & videos access** — "Allow all" chuno. "Select photos" (kuch
   photos) chunoge to app sirf unhi ko dekh payega aur baaki gallery invisible
   rahegi; app yeh saaf bata dega.
3. **Albums** — jin albums ki copies chahiye unhe tick karo. Tile ko der tak
   dabao to us album ki latest photo gallery mein khulti hai. Neeche "Scroll to
   see all N albums" dikhe to aur albums neeche hain.
4. **Notifications** — optional; sirf zaroori alerts ke liye.
5. **Background / battery** — teen switch:
   - *Battery: no restrictions* — Android khud batata hai on hai ya off.
     Off rahe to background runs ghanton late ho sakte hain.
   - *Auto-launch* aur *Background activity* — yeh phone banane wale (Realme,
     Oppo, Xiaomi, Vivo) ke apne switch hain; Android app ko inka state
     padhne nahi deta, isliye app "Check" dikhata hai aur page kholta hai.
     Realme/Oppo par (Realme C25s, realme UI 2 bhi): Settings › Battery › App
     battery management › CloudSaver › yahin teeno switch hain - Allow
     foreground activity, Allow background activity, Allow auto-launch - aur
     "Don't optimise" bhi. Kuch phones par auto-launch Settings › App
     management › App list › CloudSaver ke andar hota hai. App ka "Open"
     button seedha isi page par le jaata hai.
   Yeh sab baad mein bhi Settings › Permissions and battery se dikhte aur badalte
   hain.
6. **Usage access** — optional lekin recommended: isse app dekh sakta hai ki
   cloud app ne kitna data bheja, jisse upload confirm hota hai. Grant karke
   wapas aao to step khud "Done, next" dikhata hai.
7. **Cloud app** — installed app apne aap detect hota hai. Cloud app mein sirf
   `Pictures/CloudSaver` folder ka backup on karo; Camera album ka backup band
   rakho, warna har photo do baar upload hogi (setup mein yeh warning dikhti hai).
8. **Ready** — summary dekho, Done.

## Roz kya hota hai

- App background mein (Smart mode: charging/idle par bhaari kaam; battery par
  din mein kuch photos) copies banata hai aur folder mein daalta hai.
- Cloud app folder upload karta hai. Jab copy folder se gayab ho jaati hai ya
  us din ka upload total copy ko cover karta hai, app usse "backed up" maanta hai.
- Folder ki jagah (space limit) bhar jaaye to app rukta hai aur Home par wajah
  likhta hai; confirmed copies khud clear hoti hain.

## Free up space

Storage › Free up space mein sirf wahi originals aate hain jinki copy cloud ne
sach mein collect kar li (proof ke saath). Teen modes: original ki jagah light
copy rakho, poori tarah hatao, ya sirf app ki apni copies hatao. Har removal
Android ke apne dialog se hota hai; "Delete permanently" ke alawa files gallery
trash mein 30 din rehti hain (Android 11+).

## Kabhi nahi hoga

- Original apne aap delete — nahi.
- Kuch bhi upload ya internet — nahi (permission hi nahi hai).
- Ads, analytics, account — nahi.

## Kuch gadbad lage to

- Home par chips (Settings ki dot bhi) batate hain kya atka hai.
- Settings › Permissions and battery: har permission ka live state aur exact page.
- Help › Logs: log share karke bhej sakte ho (isme photos nahi, sirf app ki
  apni entries).
