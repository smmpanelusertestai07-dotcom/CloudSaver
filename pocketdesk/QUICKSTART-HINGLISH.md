# PocketLinux — 5 minute quick start (Hinglish)

## 1. Install karo

1. `PocketLinux-v12.0.5-release.apk` open karo → *Install* → "unknown apps" ka prompt aaye to allow karo.
2. Play Protect ka warning aa sakta hai kyunki APK self-signed hai. *More details → Install anyway*.
3. Purana version upar hi install ho jata hai — Linux computer, apps, logins sab waise ke waise.
   Opening pe pehle app ka logo aur naam, phir Tux ke saath "Powered by Linux · Ubuntu 24.04 LTS", phir Home.
4. App ka naam ab **PocketLinux** hai. Package wahi hai, isliye yeh purane version ke upar hi
   install hota hai — Ubuntu, apps, logins sab bache rehte hain, kuch dobara download nahi hota.
   Pehli baar desktop kholne par purane Windows layer ke launchers aur prefixes apne aap saaf ho
   jaate hain; baaki kuch nahi chhua jaata.

### 12.0.5: screenshots wale fixes

- **Antigravity crash (SIGSEGV, exit 139) fix.** WebGL software path in-process GPU me fault kar
  raha tha — Cursor/Antigravity ab `--disable-3d-apis` ke saath khulte hain, terminal DOM renderer
  use karta hai. Phone pe GPU hai hi nahi, toh kuch khoya nahi.
- **App window screen se bahar nahi jayegi.** dpi badhne se Chromium apps 1.86x ho gaye the aur
  unki minimum width screen se badi. Ab scale desktop ki chhoti side se nikalta hai. App ka text
  bada karna ho to app ke andar **Ctrl aur +**.
- **Desktop icon ke naam beech me nahi tootenge** — pcmanfm ka label hamesha 100 px chauda hota
  hai, isliye desktop ka font ab fixed 18 px hai ("Antigravity" bhi ek line me), icon 128 px tak.
- **Kaala start screen gaya** — pehle second me navy, aur viewer "Starting…" card dikhata hai jab
  tak desktop bana nahi.
- **Volume panel** ab glass, **× button**, bahar tap karo to band. **Phone ▾** naya menu: volume,
  mic, photo, file add, paste, touch lock — Screen menu ab sirf picture ke liye.
- Purani error reports naye version pe khud saaf; notification category pehle se; **Bin** desktop pe.

### 12.0.0 me kya naya hai

- **Rotation ab sach me kaam karta hai.** Settings → Screen rotation → *Portrait* ka matlab ab
  sirf phone ki window nahi, andar ka Linux computer bhi portrait. Auto-rotate ab ulta
  (camera neeche) nahi hota, aur phone ka apna rotation lock use nahi rokta — kyunki tumne
  PocketLinux me Auto-rotate chuna hai, wahi chalega.
- **Andar ka text ab padha ja sakta hai.** dpi ab phone ki apni screen se nikalta hai (pehle
  fix 120 tha, jiski wajah se sab kuch phone ke apne text ka do-tihai dikhta tha). Chalte hue
  desktop pe turant bada karna ho to: **Screen → Bigger interface**.
- **Icons theek.** Projects, System settings, Software aur "Install a downloaded app" — sabke
  apne icon. Ubuntu 24.04 ke Adwaita me app icons rahe hi nahi, isliye Software ka icon khali
  dikh raha tha.
- **System settings** ab computer ke andar ek jagah: theme, bar ki position, sound, storage,
  software. Aur computer ka theme ab app ke Light/Dark/System ko follow karta hai.
- **Phone files ab sirf 6 folder.** Download, DCIM, Documents, Pictures, Music, Movies — aur
  kuch bhi computer se reach nahi hota. Sirf ek file deni ho to Phone files on karne ki zarurat
  hi nahi: **Window → Add a file from the phone or a cloud drive** — ye Android ka apna picker
  kholta hai, jisme Drive aur baaki cloud apps bhi dikhte hain.
- **Viewer:** volume right corner me (+ / mute / −), bar pe **Mute** button, **rotation lock**
  aur **screen lock** (do baar tap se khulta hai), teesra touch mode **Screen** (game/drag ke
  liye), side-scrolling, window ko drag karke resize, keyboard khulne pe layout upar.
- **Tez.** CopyRect (scroll pe pura page dobara nahi bhejta), 1:1 sharp blit, splash 1.6 sec.
- **2 GB phone bhi.** Linux computer chalega, AI apps nahi — app pehle hi bata deta hai.
- **Background me kaam count hota hai.** Build ya AI agent chal raha ho to "kuch touch nahi
  kiya" kehke session band nahi hota.
- **Design and game tools:** Blender, Godot, GIMP, Inkscape (ARM64, Ubuntu se).
- **Settings → Terms** — chhota, saaf, zaroori jitna.

### 11.0.5: computer ab khud ko chalta rakhta hai

Pehle jo hota tha: desktop achanak band, "The desktop display ended unexpectedly (exit 137)".
Wo **memory ki problem nahi thi** — report me 1.2 GB free tha aur lowMemory false. Asli wajah:
**Android 12+ ek app ke 32 se zyada forked processes ko ek saath maar deta hai**, aur PRoot me
har Linux process usi app ka process hai. Report me peak **36** tha — jisme **5 zombie** the
(khatam ho chuke processes jinhe container me koi wait nahi kar raha tha, kyunki container me
init hota hi nahi).

Ab teen cheezein apne aap hoti hain, bina kisi setting ke:

- **Zombie clear hote rehte hain.** Session ab subreaper hai, toh jo processes orphan ho jaate
  hain wo yahan aate hain aur clear ho jaate hain. Jo processes kisi ke apne hain (display,
  panel, installer) unhe chhua nahi jaata.
- **26 pe ruk jaata hai.** Agar computer 26 processes par kuch second tak ruka rahe, to ek
  program (pehle browser) band kar diya jaata hai aur tumhe bataya jaata hai — 32 par Android
  poora computer band kar deta, ye usse behtar hai.
- **Band ho jaye to khud khul jaata hai.** Jo session apne aap band ho, wo **do baar apne aap
  reopen** hota hai; viewer screen par "Reopening…" dikhta hai, Home par nahi feka jaata. Teesri
  baar rukta hai aur saaf reason deta hai.

**Settings → Running → Android process limit hata diya gaya hai.** Wo developer options maangta
tha, poore phone ki setting badalta tha, aur jise mila hi nahi uske liye kuch nahi karta tha.
Ab system khud manage karta hai.

### Desktop ke controls

- Sidebar/settings cut ho rahe hon: desktop toolbar mein **Screen → Wider workspace**.
  Zyada content fit hoga, text chhota dikhega; pinch se zoom kar sakte ho.
- Sidebar ka size badalna: **Mouse** mode mein pointer divider par rakho → **Drag**
  dabao → swipe karo → **Release**. Ungli utha kar dobara swipe kar sakte ho.
- **Keys** row mein **Shift** hai. Shift tap karke arrow/text key dabao; modifier
  us key ke baad release hota hai. Shift ke saath Drag selection bhi kar sakte ho.
- Lambi background job ke liye Settings → Running → **When to stop by itself → Never stop**.
  Pehle se chuna timer update khud nahi badalta. Overheat/data guards alag hain.
- Background mein viewer pixel requests rukte hain; wapas aane par badle hue
  pixels refresh hote hain. Linux work ke liye alag CPU wake lease hai. Android firmware
  phir bhi process stop kar sakta hai; physical RAM aur GPU capacity wahi rehti hai.

## 2. Home tab: pehli baar setup

1. App kholo. Neeche 3 tabs: **Home · Apps · Settings**.
2. Upar 4 tiles: **Network, Battery, Free space, Temperature**. Neeche *Your phone* card me
   **Your phone is compatible** likha hona chahiye — tap karo to detail wahin niche khulti hai.
3. **Set up Linux** dabao → confirm karo. Ubuntu 24.04 LTS download hoga (30 MB), phir ek hi baar me
   desktop, sound, Google Chrome aur developer tools (gcc/make, Python 3, Node.js, Git, SSH) — lagbhag
   550 MB packages, 15–45 min. 6 GB free chahiye. Beech me net kat jaye to wahi se resume hota hai.
4. Kuch galat ho to Home pe **Needs attention** card dikhega — har row tap karo, wahi fix ya detail khulta hai.
5. **Linux only, on purpose** card me likha hai computer Linux hi kyun hai — har line tap karo,
   facts (dates ke saath) wahin khulte hain.

## 3. Apps tab

### AI desktop apps
- **ChatGPT** (AI assistant + Codex coding agent), **Claude Desktop** (AI assistant + Claude Code),
  **Cursor** (AI code editor / IDE), **Antigravity** (Google ka agentic development platform / IDE).
- Chaaron **maker ke apne official Linux app** hain — web page nahi, command line nahi.
  Row tap → install. Ek baar install; row dubara tap = update, login waise ka waisa.
- **Desktop khula ho tab bhi install ho jata hai** — computer chalta rehta hai, app ready hone pe
  uska icon desktop pe aa jata hai.
- Ek waqt me **ek hi AI app** kholo — 4 GB RAM wale phone pe do saath me memory kha jate hain.

### Baaki sab setup ke saath hi aa gaya
- Desktop, sound, **Google Chrome** (computer ka ek hi browser) aur **developer tools** (gcc/make, Python 3, Node.js, Git, SSH, jq, htop, vim)
  sab setup me hi install ho jaate hain — Apps tab me sirf 4 AI apps hain.
- Purane computer ko update karna ho: Settings → Storage → **Update the computer's basics**.
- Desktop → Tools → **Software** me Ubuntu ke signed ARM64 packages search/install/update kar sakte ho.
- **Android app development:** Apps tab ka Mobile app development Java 21, Gradle, adb, fastboot,
  aapt aur scrcpy lagata hai. Desktop → Tools → Phone app testing se isi phone (Wireless debugging,
  127.0.0.1) ya same Wi-Fi ke dusre phone par APK install, auto-open, app logcat aur screen mirror hota hai.
- **Khud ka downloaded app install karna:** desktop me Chrome se app ka Linux ARM64 `.deb` download karo → file kholo (Chrome ke download bar se ya Downloads folder se). PocketLinux ka installer khulta hai: app ka naam, version, publisher, size aur *is phone* me kitni jagah bachi hai. Processor, space, dependencies aur "unsigned file" ka check karke **Install anyway** milta hai; jo yahan chal hi nahi sakta (amd64 build, AppImage) wo reason ke saath block hota hai. Menu me **Install a downloaded app** bhi hai.
- **App hatana:** installed AI app ki row tap karo → **Uninstall**. Space wapas, baaki sab (computer, files, dusre apps) waisa hi. Computer basics uninstall nahi hote — wo computer ka hissa hai.

### Windows apps kyun nahi

Yeh app ab **sirf Linux** hai, jaan-boojh kar. Teen alag deewarein hain, koi ek bhi kaafi hai:

1. Asli Windows ke liye virtual machine chahiye, aur Android ka virtualisation framework
   documented hai ki wo privileged/platform apps ke liye hai — ek installed app usse chala hi
   nahi sakta.
2. Jis ek project ne ARM64 pe Windows programs chalaye the, usne Android support hata diya.
3. Yeh container khud har system call ptrace se trace karta hai, aur uske upar instruction
   translator wahi combination hai jo toot-ta hai.

Aur jahan layer chalta bhi hai wahan bhi ghaata hai: do sabse important apps Windows pe store
package hain, jo layer me package identity ke bina install hote hain — isliye custom link se
aane wala sign-in aur app ka apna updater dono toot jaate hain. Ye Chromium apps hain: translate
hone par sandbox chala jaata hai aur ~30% zyada RAM lagti hai, us phone par jiske paas 4 GB se
kam hai. Aur jis ek feature ke liye log Windows build chahte hain — apps ka apna Computer Use —
wo *Windows* programs ko *Windows* automation se chalata hai, isliye layer ke andar wahi sabse
pehle tootta hai. PocketLinux wo capability khud deta hai (appshot + click/type/key/scroll,
MCP se kisi bhi AI agent ko).

ARM64 pe Linux builds **behtar** supported hain: Claude ka Cowork Windows ARM64 pe supported hi
nahi, aur Claude Code ka Windows ARM64 crash bug open hai. Chaaron apps official Linux ARM64
build publish karte hain. macOS sirf Apple ke apne hardware pe licensed hai.

Aur lambi race: Ubuntu 24.04 LTS ko April 2029 tak, Ubuntu Pro ke saath April 2036 tak, aur
Legacy add-on ke saath April 2039 tak security updates milte hain. Windows ka har release ~24
mahine chalta hai. Ek baar set karke chhod dene wale computer ke liye yeh koi muqabla hi nahi.

Downloaded `.exe`/`.msix` khologe to installer साफ़ bata dega ki kyun nahi chalega, aur Linux
ARM64 build dhoondhne ko kahega — chup-chaap fail nahi hoga.

### Sign in kaise hoga
- **ChatGPT:** email daalo. Account Google se bana hai to Google ka sign-in page khulega — normal hai, wahi complete karo. Browser apne aap app me wapas bhej deta hai.
- **Claude:** email daalo. Anthropic mail me code ya link bhejta hai. Phone ke Gmail me mail kholo — code aaya to seedha app me daalo; link aaya to link kholo, jo page khule usme code milega, wo app me daalo.
- Ek baar sign in kaafi hai — stop/restart/update ke baad bhi signed in rehta hai.

## 4. Open desktop: Linux computer ki screen

Neeche (ya upar, tumhari marzi) **ek hi bar**:

| Button | Kya karta hai |
| --- | --- |
| Home | PocketLinux home pe wapas (computer chalta rehta hai) |
| **Linux computer** | status; tap karo to details |
| **Screen ▾** | Fit, Zoom in/out, Rotate, Full screen, controls upar/neeche, **Volume up/down** |
| **Finger / Mouse** | Finger = jahan chhuo wahi click, swipe = scroll (tez swipe = aage bhi scroll hota rehta hai), hold = right-click, pointer haath jaisa. Mouse = arrow ghumao, tap = click, do ungli = scroll, tap-then-drag = drag; pointer wahi shape jo desktop dikhata hai (text pe I-beam, link pe haath) |
| **Keyboard** | phone ka keyboard |
| **Keys** | Esc, Tab, Ctrl, Alt, Super, arrows, Enter, Del, Home, End, PgUp, PgDn ki row on/off |
| **Window ▾** | Close, **Force close** (atka hua app), Switch, All windows, Minimise all, Paste from phone, **Apps menu**, **Phone files**, **Reload the screen** |

- Desktop ke panel me sabse pehle **Apps** button (Tux) — saare installed apps ki list. Wahi list
  wallpaper pe right-click (Finger mode me long press) se bhi khulti hai, Super+A se bhi.
- Panel pe: Apps · AI apps · browser · Files · Terminal · **Phone files**. Khule hue windows bhi wahi dikhte hain.
- Zoom 100 % se neeche nahi jata — 100 % matlab poora desktop pehle se screen pe hai.
- Full screen me sirf ek **Controls** chip bachta hai; use kahin bhi drag karo, tap karo to bar wapas.
- Desktop ke andar har window ka close/minimise/maximise **left** side title bar me hai. Koi app
  cross dabane pe band na ho (hang) to Window ▾ → **Force close**.
- Chrome, Tools, file picker aur Windows installer ki floating window portrait/landscape badalne
  par bhi panel ke neeche ya screen ke bahar nahi jayegi. Boundary guard usko current visible area
  ke andar move/shrink karta hai; manually bhatki window ke liye Window ▾ → **Fit** bhi hai.
- **Sound** phone ke speaker se aata hai jab desktop screen khuli ho; phone ke volume buttons ab kaam karte hain.
- Copy sirf Ctrl+C se hota hai; sirf text select karne se phone pe "Copied" nahi aayega.

## 5. Settings tab

| Group | Options |
| --- | --- |
| Appearance | Theme (Match phone / Light / Dark), Screen rotation, Desktop text size (Compact / Normal / Large) |
| Running | When to stop by itself (Smart · recommended / 1–6 hours / Never), Overheat protection |
| Data and files | Mobile data limit, Wi-Fi only, **Downloads go to**: Ask every time / Computer Downloads / Phone Downloads |
| Privacy and safety | App lock — fingerprint/PIN, home aur desktop dono screen pe; on karte waqt ek baar pooch ke confirm karta hai |
| Permissions | Notifications, Battery usage (Unrestricted), **Background activity** (phone ki battery page pe Allow foreground + Allow background activity ON), **Auto-launch** (Allow auto-launch ON), **Phone files**, App info |
| Storage | Linux computer kitna space le raha hai, **Open-source notices** (APK ke andar hi hain), **Update the computer's basics** (sirf tab dikhta hai jab is version me kuch naya ho — Ubuntu ke security updates ke saath), **Delete the Linux computer and free space** |

Settings badalne se kabhi kuch delete nahi hota.

## 6. Computer apne aap kab band hota hai

- Smart mode: 25 min tak kuch na chhuo, battery 15 % se neeche (charger ke bina), phone bahut garam, ya aaj ka mobile data limit khatam.
- 15 % se neeche battery pe (charger ke bina) desktop khulega bhi nahi — Home pe likha aayega. Charger lagao, ya Settings me fixed timer / Never stop chuno.
- **Linux ChatGPT crash/slow ho:** Settings → **Linux app reports → ChatGPT** me exact startup aur exit output milega. Doosre tap ya sign-in se running app kill nahi hoti; browser bhi khula rehta hai. RAM bahut kam ho to naya heavy launch rukega aur message aayega. Ek AI app use karo, unsaved kaam save karo. Actual phone par sign-in/har feature ki guarantee nahi hai.
- Band hone pe kuch nahi jata — apps signed in rehte hain, files wahin.
- Apne aap restart kabhi nahi hota — tum **Open desktop** dabate ho.
- Bina net ke computer chalta hai (desktop, files, browser ke saved pages); AI apps ko net chahiye.

## 6a. Phone ki file ChatGPT/Claude me kaise bheje

1. Settings → Permissions → **Phone files** → Allow (Android "All files access" maangta hai).
2. Desktop dubara kholo. Ab computer ke andar **Phone files** folder hai (desktop icon, panel button, Super+P) = tumhara poora phone storage.
3. ChatGPT me attach (📎) dabao → dialog me left side **Phone**, **Phone Downloads**, **Phone Photos**, **Phone Documents** — wahi se file chuno. Computer ki apni files **Computer Downloads** aur **Projects** me hain.
4. Phone folder me save karoge to file phone me chali jaati hai. Off karna ho to Settings → Permissions → Phone files tap karo, Android ki page me All files access hata do.

## 7. Files kahan jati hain

- Kaam: `/home/coder/Projects` (computer ke andar).
- Computer Downloads: `/home/coder/Downloads` — private, sirf PocketLinux ke andar.
- Phone Downloads: `/home/coder/Phone/Download/PocketLinux` — Android Files me dikhta hai; Phone files permission chahiye.
- Settings → Data and files → **Downloads go to** me har file ke liye poochna, Computer, ya Phone choose karo. Setting badalne par purani file move/delete nahi hoti.
- Shared: `/home/coder/Shared` — bahar nikalne ka rasta. Yehi ek folder phone ke Files app me `Android/data/com.pocketlinux/files/Shared` par dikhta hai. File manager me bhi bookmark hai.
- Uninstall karne se poora computer delete ho jata hai — aur `Downloads` aur `Shared` dono app ke hi andar hain, wo bhi jaate hain. Rakhna hai to file **phone ke apne folder** me le jao: Settings → Permissions → **Phone files** ON karke seedha phone ke `Download`/`Documents` me save karo, ya `Shared` me daal kar phone ke Files app se (`Android/data/com.pocketlinux/files/Shared`) apne `Download` me copy kar lo.

## Privacy — chhoti si baat

- Desktop ki screen aur uski awaaz ab **private socket** se aati hai (app ke apne storage ke andar), koi network port khula nahi rehta — phone ka koi dusra app na dekh sakta hai na sun sakta hai.
- App lock ON ho to recents me bhi app ka screenshot nahi dikhega.
- Setup beech me ruk gaya ya computer ka koi hissa gayab ho gaya to app usse **repair** karta hai, delete nahi — delete sirf Settings se, warning ke saath.

## Kya local phone ke andar kaam nahi karega (permanent)

- Windows ya macOS — phone pe koi normal unrooted app hardware virtual machine bana hi nahi sakta, aur macOS sirf Apple ke apne computer pe licensed hai. Compatibility layer bhi option nahi (upar “Windows apps kyun nahi” dekho).
- Docker, KVM, kernel modules — Android ka kernel share hota hai.
- amd64-only software, snap, flatpak, AppImage — native ARM64 `.deb` use karo.
- Microphone: desktop Screen → Microphone se on hota hai; har start pe off aur desktop chhodte hi band.
