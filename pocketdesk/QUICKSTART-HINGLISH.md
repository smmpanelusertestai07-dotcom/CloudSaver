# PocketDesk — 5 minute quick start (Hinglish)

## 1. Install karo

1. `PocketDesk-3.2.0.apk` open karo → *Install* → "unknown apps" ka prompt aaye to allow karo.
2. Play Protect ka warning aa sakta hai kyunki APK self-signed hai. *More details → Install anyway*.
3. Purana version upar hi install ho jata hai — Linux computer, apps, logins sab waise ke waise.

## 2. Home tab: pehli baar setup

1. App kholo. Neeche 3 tabs: **Home · Apps · Settings**.
2. Upar 4 tiles: **Network, Battery, Free space, Temperature**. Neeche *Your phone* card me
   **Your phone is compatible** likha hona chahiye — nahi to tap karke dekho kya kam hai.
3. **Set up Linux** dabao → confirm karo. Ubuntu download hoga (30 MB), phir desktop packages
   (10–30 min). Beech me net kat jaye to wahi se resume hota hai.
4. Kuch galat ho to Home pe **Needs attention** card dikhega — har row tap karo, wahi fix khulta hai.

## 3. Apps tab: AI desktop apps

- **ChatGPT** (Codex ke saath), **Claude Desktop** (Claude Code ke saath), **Cursor**, **Antigravity**.
  ChatGPT aur Claude roz ke sawaal, likhna, kaam ke liye; Cursor aur Antigravity software banane ke liye.
- Chaaron **maker ke apne official Linux desktop app** hain — web page nahi, command line nahi.
  Row tap → install. Ek baar install; naye features apne aap; row dubara tap = update, login waise ka waisa.
- Ek waqt me **ek hi AI app** kholo — 4 GB RAM wale phone pe do saath me memory kha jate hain.
- Koi aur Linux app chahiye to desktop ke browser se khud install kar sakte ho — *Anything else,
  from the browser* card me likha hai kya chalega (arm64 .deb, aarch64 AppImage) aur kya nahi (amd64, snap, .exe, .apk).

### Sign in kaise hoga
- **ChatGPT:** email daalo. Account Google se bana hai to Google ka sign-in page khulega — normal hai, wahi complete karo. Browser apne aap app me wapas bhej deta hai.
- **Claude:** email daalo. Anthropic mail me code ya link bhejta hai. Phone ke Gmail me mail kholo — code aaya to seedha app me daalo; link aaya to link kholo, jo page khule usme code milega, wo app me daalo.
- Ek baar sign in kaafi hai — stop/restart/update ke baad bhi signed in rehta hai.

## 4. Open desktop: Linux computer ki screen

Upar (ya neeche, tumhari marzi) **ek hi bar**:

| Button | Kya karta hai |
| --- | --- |
| Home | PocketDesk home pe wapas (computer chalta rehta hai) |
| **Linux computer** | status; tap karo to details |
| **Screen ▾** | Fit (poora desktop), Zoom in/out, Rotate, Full screen, controls upar/neeche |
| **Finger / Mouse** | Finger = jahan chhuo wahi click, swipe = scroll, hold = right-click. Mouse = arrow ghumao, tap = click, do ungli = scroll, tap-then-drag = drag |
| **Keyboard** | phone ka keyboard |
| **Keys** | Esc, Tab, Ctrl, Alt, Super, arrows, Enter, Del, Home, End, PgUp, PgDn ki row on/off |
| **Window ▾** | Close, **Force close** (atka hua app), Switch, All windows, Minimise all, Paste from phone |

- Zoom 100 % se neeche nahi jata — 100 % matlab poora desktop pehle se screen pe hai.
- Full screen me sirf ek **Controls** chip bachta hai; use kahin bhi drag karo, tap karo to bar wapas.
- Desktop ke andar har window ka close/minimise/maximise **left** side title bar me hai, taki portrait me bhi hamesha dikhe.
- Copy sirf Ctrl+C se hota hai; sirf text select karne se phone pe "Copied" nahi aayega.

## 5. Settings tab

| Group | Options |
| --- | --- |
| Appearance | Theme (Match phone / Light / Dark), Screen rotation, Desktop text size (Compact / Normal / Large) |
| Running | When to stop by itself (Smart · recommended / 1–6 hours / Never), Overheat protection |
| Data and files | Mobile data limit per day (midnight reset; limit pe downloads aur desktop dono rukte hain), Download on Wi-Fi only, Downloads visible to the phone |
| Privacy and safety | App lock — fingerprint/PIN, home aur desktop dono screen pe; on karte waqt ek baar pooch ke confirm karta hai |
| Permissions | Notifications, Battery usage (Unrestricted karo), Auto-start, App info |
| Reports | **Why an app didn't open** (app ki log, Share button), Last error report |
| Storage | Linux computer kitna space le raha hai, **Remove the Linux computer and free space** |

Settings badalne se kabhi kuch delete nahi hota.

## 6. Computer apne aap kab band hota hai

- Smart mode: 25 min tak kuch na chhuo, battery 15 % se neeche (charger ke bina), phone bahut garam, ya aaj ka mobile data limit khatam.
- Band hone pe kuch nahi jata — apps signed in rehte hain, files wahin. Home pe likha aata hai kab aur kyun band hua.
- Apne aap restart kabhi nahi hota — tum **Open desktop** dabate ho.
- Bina net ke computer chalta hai (desktop, files, browser ke saved pages); AI apps ko net chahiye.

## 7. Files kahan jati hain

- Kaam: `/home/coder/Projects` (computer ke andar).
- Downloads: `/home/coder/Downloads`. **Downloads visible to the phone** on ho (default) to phone ke Files app me `Android/data/com.pocketdesk/files/Shared/Downloads` me bhi dikhta hai; off ho to sirf computer ke andar.
- Uninstall karne se poora computer delete ho jata hai — pehle Downloads me copy kar lo.

## Kya kaam nahi karega

- Windows ya macOS — yeh Linux container hai, hardware VM nahi.
- Docker, KVM, kernel modules — Android ka kernel share hota hai.
- amd64-only software, snap, flatpak — sirf ARM64 .deb / AppImage chalega.
- ChatGPT ka Computer Use, Claude ka Cowork — Linux/phone pe maker deta hi nahi.
