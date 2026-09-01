# PocketDesk — 5 minute quick start (Hinglish)

## 1. Install karo

1. Purana **NexaDesk / NexaDock** app pehle uninstall karo. Package badal gaya hai, isliye
   Android ise naya app maanta hai.
2. `PocketDesk-1.0.0-arm64.apk` open karo → *Install* → agar "unknown apps" ka prompt aaye to
   allow karo.
3. Play Protect ka warning aa sakta hai kyunki APK self-signed hai. *More details → Install anyway*.

## 2. Pehli baar setup

1. App kholo. Upar 4 tiles dikhenge: **Network, Battery, Free space, Temperature**.
2. Free space kam se kam **4 GB** hona chahiye.
3. **Install Linux** dabao → confirm karo.
4. Ab Ubuntu download hoga. Progress me MB, speed aur time dikhega, jaise
   `142 MB of 289 MB · 1.4 MB/s · about 2 min left`.
5. Uske baad desktop packages install honge — yeh sabse lamba step hai (10–30 min, net speed pe
   depend karta hai). Beech me net kat jaye to app wahi se resume karta hai.
6. Mobile data by default allowed hai. Sirf Wi-Fi chahiye to Settings me **Download on Wi-Fi only**
   on kar do.

## 3. Desktop chalao

- **Open desktop** dabao. Ubuntu ka Openbox desktop full screen me khulega.
- Toolbar me:
  - back icon — home screen pe wapas
  - **Touchpad / Direct touch** — pointer ka tarika badlo
  - keyboard icon — phone ka keyboard kholo
  - **Paste** — Android clipboard ko Linux me bhejo
- Neeche coding key row hai: Esc, Tab, Ctrl, Alt, Super, arrows, Enter, Backspace, Delete.
- USB ya Bluetooth mouse aur keyboard laga sakte ho — dono seedha kaam karte hain.
- Right click: touchpad mode me two-finger tap, ya mouse ka right button.

## 4. ChatGPT / Codex (optional)

1. Home screen pe **Add ChatGPT** dabao.
2. OpenAI ka official Linux ARM64 package install hoga, jisme **Codex** bhi aata hai.
3. Iske liye ~2.5 GB extra space chahiye. 4 GB RAM wale phone pe yeh slow chalega — sach yahi hai.
4. Install ke baad desktop kholo aur **ChatGPT** icon dabao.
5. Login aur usage limit OpenAI ke account ka hai, app iska control nahi karta.
6. **Computer Use** feature Linux pe available nahi hai — yeh OpenAI ki taraf se hai, app ki kami
   nahi.

## 5. Settings

| Option | Kya karta hai |
| --- | --- |
| Appearance | Match phone / Light / Dark |
| Screen rotation | Automatic / Portrait / Landscape |
| Auto-stop timer | Off / 1 / 2 / 4 / 6 ghante — default 4 |
| Download on Wi-Fi only | Off = mobile data allowed |
| Overheat protection | Garam hone pe Linux band kar deta hai |

## 6. Permissions card

- **Notifications** — progress aur Stop button dikhane ke liye
- **Battery usage** — "Unrestricted" kar do, warna Android lambi session band kar deta hai
- **Auto-start** — Realme/ColorOS ki auto-start list kholta hai
- **App info** — Android ka poora app page

## 7. Phone health

- 45 °C pe warning, 49 °C pe Linux apne aap band.
- 3% battery pe band (charging me nahi).
- Resolution 1280×720 fixed hai — isse RAM, data aur garmi teeno kam rehti hai.
- Roz 3 ghante use karne ke liye 4 ghante ka default timer theek hai.

## 8. Space wapas chahiye?

*Your phone* card me **Remove Linux and free space** dabao. Shared folder ki files delete nahi hoti.

## Kya kaam nahi karega

- Windows ya macOS — yeh Linux container hai, hardware VM nahi.
- Docker, KVM, kernel modules — Android ka kernel share hota hai.
- amd64-only software — sirf ARM64 chalega.
- ChatGPT ka Computer Use — Linux pe OpenAI deta hi nahi.
