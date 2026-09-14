# PocketLinux — 5 minute quick start (Hinglish)

## 1. Install karo

1. `PocketLinux-v13.0.0-release.apk` open karo → *Install* → "unknown apps" ka prompt aaye to allow karo.
2. Play Protect ka warning aa sakta hai kyunki APK self-signed hai. *More details → Install anyway*.
3. **Zaroori, ek hi baar ki baat: yeh version purane PocketLinux ke upar install NAHI hoga.**
   Is release ki signing key badal gayi hai (purani key repository me khuli padi thi, isliye use
   compromised maana jaata hai). Android update sirf usi signer se leta hai, toh purana app phone
   me hote hue naya install fail ho jayega.
4. Isliye pehle purana app **uninstall** karna padega -- aur uninstall poora Linux computer le
   jaata hai: Ubuntu, uske andar ke apps, unke logins, `Downloads` aur `Shared`. **Jo rakhna hai
   wo pehle phone ke apne folder me le jao**: Settings → Permissions → **Phone files** ON karke
   seedha phone ke `Download`/`Documents` me save karo, ya `Shared` me daal kar phone ke Files app
   se (`Android/data/com.pocketlinux/files/Shared`) copy kar lo. Uninstall ke baad naya APK install
   karo; Ubuntu dobara download hoga aur setup phir se chalega. Opening pe pehle app ka logo aur
   naam, phir Tux ke saath "Powered by Linux · Ubuntu 24.04 LTS", phir Home.

### 13.0.0: final update

Yeh section do hisson me hai. Pehle akhri audit me jo badla, phir usse pehle wali list.

- **Bar ab poori screen me aati hai.** Pehle das buttons ek chhupe hue scroller me the, matlab
  do-tihai buttons screen se bahar the aur pata bhi nahi chalta tha. Ab bar pe paanch cheezein
  hain -- Home, status, Keyboard, pointer mode, aur **More ▾** -- aur baaki sab More ▾ ke andar
  hai (chaar hisson me). Jo cheez pehle Screen ▾, Window ▾ ya Phone ▾ me thi, wo ab More ▾ me hai.
- **Rotation ab phone ki sunta hai.** Jisne rotation setting kabhi kholi hi nahi, uske liye desktop
  phone ke rotation lock ko ignore kar deta tha. Ab bina chuni hui setting = phone jo kahe wahi;
  aur tumne khud Auto-rotate/Portrait/Landscape chuna hai to wahi chalega.
- **Picture ab compress hoti hai.** Pehle har frame bina compression ke jaata tha. Ab ZRLE use
  hota hai, chhote phone pe 16-bit pixels maange jaate hain, aur frames screen ke hisaab se paced
  hote hain. JPEG jaisa koi loss nahi hota, toh text sharp rehta hai. "Smooth nahi hai" wali
  shikayat yahin se thi.
- **Display ab kisi port pe sun-ti hi nahi.** Pehle private socket fail hone par desktop
  `127.0.0.1:5901` pe bina password khul jaata tha, aur Android pe loopback har app ko milta hai.
  Ab wo fallback hai hi nahi (`-rfbport -1`), socket ko poora time milta hai, aur na khule to app
  saaf wajah bata kar ruk jaata hai.
- **Panel me khuli windows ke buttons ke liye jagah.** Pehle launchers ne poora panel kha liya tha
  aur khuli window ka koi button bachta hi nahi tha. Ab teen window buttons ki jagah pehle rakhi
  jaati hai, launchers uske baad jitne fit ho.
- **Phone ke folder me delete ab sach me poochta hai.** Purana guard `.Trash-0` naam se bana tha,
  lekin desktop `coder` user ke naam se chalta hai, toh GLib chup-chaap tumhare DCIM/Download ke
  andar hidden bin bana kar file wahan daal deta tha. Ab user id container ke apne `/etc/passwd`
  se padha jaata hai, `.Trash-0` bhi blocked rehta hai, aur `Shared` folder bhi cover hota hai.
- **Battery/Permissions ka asli screen.** Do rows pehle hamesha "CHECK" likhti thi aur kuch padhti
  hi nahi thi. Ab Battery usage asli state dikhata hai, aur maker ke switch ka **raasta** likha
  hota hai (section 8 dekho). Notification ek hi baar poochha jaata hai, aur ab do alag categories
  hain taaki permanent wali ko silent karne se setup progress na chali jaye.
- **Naam har jagah PocketLinux.** Folder, saare scripts, workflow, container ke andar ke config
  folder, sab. Behaviour kuch nahi badla.
- **Cloud/phone se file lena safe hua.** Copy se pehle free space check hota hai, badi file cancel
  ho sakti hai, aur `.env` jaisi dotfile ka naam ab nahi katta.
- **Computer ki apni Settings** ab band karne tak khuli rehti hai, rows grouped hain, theme me
  "Follow the phone" hai, aur wallpaper set karne ka option hai. Tumhare dragged icons, wallpaper
  aur file-dialog bookmarks ab har start pe mit-te nahi.
- **Jo dawe code nahi nibhata tha, wo hata diye gaye** -- jaise "Screen mode phone jaisa multi-touch
  hai" (aisa ho hi nahi sakta, section 4 dekho) aur Ubuntu ki do alag-alag support dates.

Usse pehle 13.0.0 me:

- **Antigravity sign-in fix.** Chrome me "successfully authenticated" ke baad app "could not open:
  error 127" bolta tha: xdg-open launcher ki Exec line ko quote samjhe bina todta hai, toh label
  `"Antigravity` aur command `-` ban gaya. Ab label Exec line pe hai hi nahi (ek table me hai).
  ChatGPT ke callback me bhi yahi bug tha.
- **Volume:** panel Keys row ke neeche baithta hai (upar bar hone pe), Mute button start pe phone ki
  asli state padhta hai, slider 0 pe ho to Unmute sach me awaaz wapas lata hai, key se aaya panel
  screen chhodne pe band ho jata hai.
- **Phone files:** off hone pe khali folders/note saaf; phone ke folder me delete karne pe file
  chhupe `.Trash` me nahi jaati, file manager pehle poochhta hai; phone/cloud se copy hui file
  poori hone pe hi apna naam leti hai; Wireless debugging pairing ka risk safety answer me likha hai.
- **Computer Settings/Software:** sahi row names, apt ke faltu warnings gaye (apt-utils + sandbox
  off), theme change nayi windows pe turant, panel mar jaye to refresh usse wapas lata hai.
- **Keyboard ab sach me bar ko upar uthata hai (Android 11–14):** window edge-to-edge na hone se
  keyboard ka inset 0 aata tha; ab window edge-to-edge hai, Android 10 pe window resize hoti hai.
- **Window kabhi screen se bahar nahi:** Chromium scale ab live desktop size ke saath badalta hai
  (guard likhta hai), Bigger interface wahi tak jaata hai jahan tak window fit ho (portrait 115 %,
  landscape sab); rotation lock kabhi ulta pin nahi karta; title bar ke close/minimise 44 px ke;
  Resize by dragging turant; Chrome ka "Restore pages?" bubble nahi.
- **Auto-reopen fix:** desktop khud band ho jaye to reopen ab zinda service me hota hai (pehle
  notification, wake lock aur heartbeat ke bina chalta tha).
- **Touch/keys/clipboard:** Screen mode me do ungli se scroll, pinch pe galat click nahi; Keys row
  me **F1–F12**; phone se paste ab type hota hai (terminal me bhi chalta hai, Hindi/emoji bhi);
  Linux se copy phone ke clipboard se compare hota hai; dead keys se accent ban-te hain.
- **Security:** loopback port (5901/4712) tabhi try hota tha jab desktop khud bole ki fallback
  hua; ab display wala fallback poori tarah hata diya gaya hai (upar dekho); MCP ka phone_shell
  sirf app-testing commands chalata hai (am, pm, input, logcat, dumpsys…).
- **Texts:** Google ke Android Linux Terminal se comparison (Pixel-only GPU, VM vs PRoot), Ubuntu
  24.04 kyun (26.04 aane ke baad bhi), sab AI apps ke Linux builds 11 Sep 2026 ko dobara verify.

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
- **Volume panel** ab glass, **× button**, bahar tap karo to band. Volume, mic, photo, file add,
  paste aur touch lock ek jagah aa gaye; is release ke baad ye sab **More ▾** ke Phone waale
  hisse me hain.
- Purani error reports naye version pe khud saaf; notification category pehle se; **Bin** desktop pe.

### 12.0.0 me kya naya hai

- **Rotation ab sach me kaam karta hai.** Settings → Screen rotation → *Portrait* ka matlab ab
  sirf phone ki window nahi, andar ka Linux computer bhi portrait. Auto-rotate ab ulta
  (camera neeche) nahi hota, aur phone ka apna rotation lock use nahi rokta — kyunki tumne
  PocketLinux me Auto-rotate chuna hai, wahi chalega.
- **Andar ka text ab padha ja sakta hai.** dpi ab phone ki apni screen se nikalta hai (pehle
  fix 120 tha, jiski wajah se sab kuch phone ke apne text ka do-tihai dikhta tha). Chalte hue
  desktop pe turant bada karna ho to: **More ▾ → Bigger interface**.
- **Icons theek.** Projects, System settings, Software aur "Install a downloaded app" — sabke
  apne icon. Ubuntu 24.04 ke Adwaita me app icons rahe hi nahi, isliye Software ka icon khali
  dikh raha tha.
- **System settings** ab computer ke andar ek jagah: theme, bar ki position, sound, storage,
  software. Aur computer ka theme ab app ke Light/Dark/System ko follow karta hai.
- **Phone files ab sirf 6 folder.** Download, DCIM, Documents, Pictures, Music, Movies — aur
  kuch bhi computer se reach nahi hota. Sirf ek file deni ho to Phone files on karne ki zarurat
  hi nahi: **More ▾ → Add a file from the phone or a cloud drive** — ye Android ka apna picker
  kholta hai, jisme Drive aur baaki cloud apps bhi dikhte hain.
- **Viewer:** volume panel (+ / mute / −), **Mute**, **rotation lock** aur **screen lock**
  (do baar tap se khulta hai) ab teeno More ▾ me hain; teesra pointer mode **Screen**
  (game/drag ke liye), side-scrolling, window ko drag karke resize, keyboard khulne pe layout upar.
- **Tez.** CopyRect (scroll pe pura page dobara nahi bhejta), 1:1 sharp blit, splash 1.6 sec.
- **2 GB phone pe bhi Linux computer khul jaata hai**, AI apps nahi — app pehle hi bata deta hai.
  Lekin browser bhi 2 GB me theek se nahi chalta; practical minimum **4 GB** hai (neeche
  "Kya nahi ho sakta" dekho).
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

- Sidebar/settings cut ho rahe hon: bar me **More ▾ → Wider workspace**.
  Zyada content fit hoga, text chhota dikhega; pinch se zoom kar sakte ho.
- Sidebar ka size badalna: **Mouse** mode me pointer divider par rakho → **More ▾ → Hold the
  mouse button down** → swipe karo → **More ▾ → Release the mouse button**. Ungli utha kar
  dobara swipe kar sakte ho.
- **Special keys** row me **Shift** hai (More ▾ → Special keys). Shift tap karke arrow/text key
  dabao; modifier us key ke baad release hota hai. Shift ke saath mouse button hold karke
  selection bhi kar sakte ho.
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

Aur lambi race: Ubuntu 24.04 LTS ko Ubuntu se **April 2029** tak security updates milte hain,
aur wo kabhi zabardasti upgrade nahi karwata. Windows ka har release ~24 mahine chalta hai. Ek
baar set karke chhod dene wale computer ke liye yeh koi muqabla hi nahi.

Downloaded `.exe`/`.msix` khologe to installer साफ़ bata dega ki kyun nahi chalega, aur Linux
ARM64 build dhoondhne ko kahega — chup-chaap fail nahi hoga.

### Sign in kaise hoga
- **ChatGPT:** email daalo. Account Google se bana hai to Google ka sign-in page khulega — normal hai, wahi complete karo. Browser apne aap app me wapas bhej deta hai.
- **Claude:** email daalo. Anthropic mail me code ya link bhejta hai. Phone ke Gmail me mail kholo — code aaya to seedha app me daalo; link aaya to link kholo, jo page khule usme code milega, wo app me daalo.
- Ek baar sign in karne ke baad login computer ke andar save rehta hai, toh stop/restart ke baad
  dobara nahi karna padta. Do baatein saaf-saaf: container me koi keyring daemon nahi chalti,
  isliye ye apps apna token **bina asli encryption ke** phone par rakhte hain (launcher
  `--password-store=basic` use karta hai) -- phone kisi aur ke haath lage to wo token padha ja
  sakta hai, isliye Settings me **App lock** on rakho. Aur ye apps phone par sach me khulenge ya
  nahi, iska test kisi asli device par nahi hua hai: package aur unki zarooratein verify hain,
  chalna nahi.

## 4. Open desktop: Linux computer ki screen

Neeche (ya upar, tumhari marzi) **ek hi bar**, aur us par sirf **paanch** cheezein -- itni hi ek
patle phone me poori dikhti hain:

| Button | Kya karta hai |
| --- | --- |
| Home | PocketLinux home pe wapas (computer chalta rehta hai) |
| **Linux computer** | status; tap karo to details |
| **Keyboard** | phone ka keyboard |
| **Finger / Mouse / Screen** | pointer mode badalta hai (neeche dekho) |
| **More ▾** | baaki sab, chaar hisson me |

**More ▾** ke andar:

| Hissa | Kya milta hai |
| --- | --- |
| Abhi ke liye | Mute / sound wapas, **Special keys** ki row on/off, **Hold the mouse button down** aur **Release the mouse button** |
| Picture | Fit, Zoom in/out, Wider workspace, Bigger interface, Rotate, Rotation lock, Full screen, Auto-hide, controls upar/neeche |
| Windows | Switch, All open apps, **Apps menu**, Fit this window, Resize by dragging, Minimise, Minimise all, Close, **Force close** (atka hua app), **Reload the screen** |
| Phone | Volume and mute (panel), Microphone, Take a photo, Add a file from the phone or a cloud drive, **Phone files**, Paste from the phone, Lock the screen (touch ignore) |

**Special keys** row: Esc, Tab, Ctrl, Alt, Super, Shift, arrows, Enter, Back, Del, Home, End,
PgUp, PgDn aur **F1-F12**.

**Teen pointer modes -- aur teeno me ek hi pointer hai.** Connection par jo pointer event jaata
hai usme ek hi jagah (x, y) aur button ka state hota hai, isliye do asli ungliyan andar ja hi
nahi sakti. Koi bhi mode multi-touch nahi hai, aur ho bhi nahi sakta. Farak sirf itna hai ki wo
ek pointer kaise chalta hai:

- **Finger** -- jahan chhuo wahi click; swipe = scroll (tez swipe ke baad scroll chalta rehta
  hai); aadha second dabaye rakho = right-click; pointer haath jaisa.
- **Mouse** -- arrow ghumao, tap = click, tap-then-drag = drag, dabaye rakho = right-click;
  pointer wahi shape jo desktop dikhata hai (text pe I-beam, link pe haath).
- **Screen** -- ungli glass par rehne tak button daba rehta hai, isliye swipe ek **asli drag**
  hai: map khisakta hai, canvas pe line banti hai, game ka on-screen control kaam karta hai.
- Teeno me **do ungli se scroll** hota hai, aur pinch se viewer ka zoom badalta hai.

- Desktop ke panel me sabse pehle **Apps** button (Tux) — saare installed apps ki list. Wahi list
  wallpaper pe right-click (Finger mode me long press) se bhi khulti hai, Super+A se bhi.
- Panel pe pehle **Apps**, uske baad jitne launcher panel ki chaudai me fit ho: Files, Terminal,
  phir browser, Phone files aur Settings. Khuli windows ke buttons ke liye jagah sabse pehle rakhi
  jaati hai (teen ke liye), isliye khuli window ka button ab hamesha milta hai. Jo launcher fit
  nahi hota uska icon desktop par aur naam Apps menu me rehta hai. AI apps jaan-boojh kar panel
  par nahi hain: app khulne ke baad uski apni window ka button hi kaam ka hota hai.
- Zoom 100 % se neeche nahi jata — 100 % matlab poora desktop pehle se screen pe hai.
- Full screen me sirf ek **Controls** chip bachta hai; use kahin bhi drag karo, tap karo to bar wapas.
- Desktop ke andar har window ka close aur minimise button **left** side title bar me hai, ungli ke size ke (maximise button nahi: har app window pehle se poori screen pe khulti hai; More ▾ → Fit this window usse wapas bhar deta hai). Koi app
  cross dabane pe band na ho (hang) to More ▾ → **Force close**.
- Settings, Software, file picker aur .deb installer ki floating window portrait/landscape badalne
  par bhi panel ke neeche ya screen ke bahar nahi jayegi. Boundary guard usko current visible area
  ke andar move/shrink karta hai; manually bhatki window ke liye More ▾ → **Fit this window** bhi hai.
- **Sound** phone ke speaker se aata hai jab desktop screen khuli ho; phone ke volume buttons ab kaam karte hain.
- Copy sirf Ctrl+C se hota hai; sirf text select karne se phone pe "Copied" nahi aayega.

## 5. Settings tab

| Group | Options |
| --- | --- |
| Appearance | Theme (Match phone / Light / Dark), Screen rotation, Desktop text size (Compact / Normal / Large) |
| Running | When to stop by itself (Smart · recommended / 1–6 hours / Never), Overheat protection |
| Data and files | Mobile data limit, Wi-Fi only, **Downloads go to**: Ask every time / Computer Downloads / Phone Downloads |
| Privacy and safety | App lock — fingerprint/PIN, home aur desktop dono screen pe; on karte waqt ek baar pooch ke confirm karta hai |
| Permissions | Notifications, **Battery usage** (yahi ek setting Android padh kar dikhata hai), **Auto-launch** aur **Background activity** (phone banane wale ke apne switch: koi app inhe padh nahi sakta, isliye row raasta likhti hai aur wahi page kholti hai -- section 8), **Phone files**, **Microphone**, **Privacy monitor**, App info |
| Storage | Linux computer kitna space le raha hai, **Update the computer's basics** (sirf tab dikhta hai jab is version me kuch naya ho, Ubuntu ke security updates ke saath), **Delete the Linux computer and free space**, **Privacy** (kya phone se bahar jaata hai, kya nahi), **Terms**, **Open-source notices** (APK ke andar hi hain) |

Settings badalne se kabhi kuch delete nahi hota.

## 6. Computer apne aap kab band hota hai

- Smart mode: 25 min tak kuch na chhuo, battery 15 % se neeche (charger ke bina), phone bahut garam, ya aaj ka mobile data limit khatam.
- 15 % se neeche battery pe (charger ke bina) desktop khulega bhi nahi — Home pe likha aayega. Charger lagao, ya Settings me fixed timer / Never stop chuno.
- **Linux ChatGPT crash/slow ho:** har app ka startup aur exit output computer ke andar
  `/home/coder/.pocketlinux/logs` me likha jaata hai -- desktop ke Files ya Terminal se kholo.
  (Settings me se log picker aur raw error screen hata diye gaye hain; files wahin hain.) Doosre
  tap ya sign-in se running app kill nahi hoti; browser bhi khula rehta hai. RAM bahut kam ho to
  naya heavy launch rukega aur message aayega. Ek AI app use karo, unsaved kaam save karo. Actual
  phone par sign-in/har feature ki guarantee nahi hai.
- Band hone pe kuch nahi jata — apps signed in rehte hain, files wahin.
- Apne aap restart kabhi nahi hota — tum **Open desktop** dabate ho.
- Bina net ke computer chalta hai (desktop, files, browser ke saved pages); AI apps ko net chahiye.

## 6a. Phone ki file ChatGPT/Claude me kaise bheje

1. Settings → Permissions → **Phone files** → Allow (Android "All files access" maangta hai).
2. Desktop dubara kholo. Ab computer ke andar **Phone files** folder hai (desktop icon, Apps menu, Super+P, aur panel par tab jab panel me jagah bache) = phone ke 6 folder (Download, DCIM, Documents, Pictures, Music, Movies).
3. ChatGPT me attach (📎) dabao → dialog me left side **Phone**, **Phone Downloads**, **Phone Photos**, **Phone Documents** — wahi se file chuno. Computer ki apni files **Computer Downloads** aur **Projects** me hain.
4. Phone folder me save karoge to file phone me chali jaati hai. Off karna ho to Settings → Permissions → Phone files tap karo, Android ki page me All files access hata do.

## 7. Files kahan jati hain

- Kaam: `/home/coder/Projects` (computer ke andar).
- Computer Downloads: `/home/coder/Downloads` — private, sirf PocketLinux ke andar.
- Phone Downloads: `/home/coder/Phone/Download/PocketLinux` — Android Files me dikhta hai; Phone files permission chahiye.
- Settings → Data and files → **Downloads go to** me har file ke liye poochna, Computer, ya Phone choose karo. Setting badalne par purani file move/delete nahi hoti.
- Shared: `/home/coder/Shared` — bahar nikalne ka rasta. Yehi ek folder phone ke Files app me `Android/data/com.pocketlinux/files/Shared` par dikhta hai. File manager me bhi bookmark hai.
- Uninstall karne se poora computer delete ho jata hai — aur `Downloads` aur `Shared` dono app ke hi andar hain, wo bhi jaate hain. Rakhna hai to file **phone ke apne folder** me le jao: Settings → Permissions → **Phone files** ON karke seedha phone ke `Download`/`Documents` me save karo, ya `Shared` me daal kar phone ke Files app se (`Android/data/com.pocketlinux/files/Shared`) apne `Download` me copy kar lo.

## 8. Battery ke switch tumhare phone pe kahan hain

Settings → **Permissions** me teen cheezein hain aur wo ek jaisi nahi hain. Farak samajh lo, phir
ye screen kabhi confuse nahi karegi.

- **Battery usage** -- yahi ek setting hai jo app sach me **padh sakta hai**. Row me wahi likha
  hota hai jo Android is waqt kehta hai, aur tap karne par wahi setting khulti hai. Agar pehle se
  allowed hai to tap seedha PocketLinux ke battery page pe le jaata hai. (Pehle wo tap ek dialog
  kholta tha jise Android already-allowed app ke liye chup-chaap band kar deta hai -- isliye
  button kuch karta hua nahi lagta tha. Wo theek ho gaya.)
- **Auto-launch** aur **Background activity** -- ye phone banane wale ke apne switch hain.
  **Koi bhi app inhe padh nahi sakta**, kisi bhi phone pe. Isliye row guess nahi karti; wo saaf
  likhti hai "Android cannot report this one" aur seedha wahi page kholti hai jahan switch hai.
  Switch on hai ya nahi, wo tumhe apni aankh se dekhna hai.
- **Tumhare realme pe raasta:** **Settings → Battery → App battery management → PocketLinux**.
  Teeno wahin ek jagah hain: *Allow auto-launch*, *Allow background activity* (aur *Allow
  foreground activity*), aur *Don't optimise*. App yahi raasta row me chhaap deta hai, phone ke
  apne menu ke shabdon me.
- Realme, Oppo, Xiaomi aur vivo phones par in me se koi switch off hona hi wo sabse aam wajah hai
  jiski wajah se computer set-up ke ek-do din baad band hone lagta hai. Ek baar dekh lo, phir
  bhool jao.
- Jis phone me ye maker-switch hote hi nahi (jaise Pixel), us phone pe ye rows dikhti hi nahi.
  Pehle wahan bhi "CHECK" likha aata tha aur banda ek aisa page dhoondhta tha jo hai hi nahi.

## 9. Isi phone pe app test karna (adb workbench)

Yeh wo cheez hai jo **sach me** kaam karti hai, aur jiske liye na cable chahiye na PC.

1. Apps tab → **Mobile app development** (Java 21, Gradle, adb, fastboot, aapt2, scrcpy).
2. Desktop me **Tools → Phone app testing**.

- **Isi phone pe.** Android 11 aur upar me *Wireless debugging* hai, aur computer isi phone ka
  network share karta hai -- toh `127.0.0.1` isi phone tak pahunchta hai. Yahan APK build karo,
  yahin install karo, aur wo isi screen pe khul jaata hai.
  Phone pe: Settings → About phone → Build number 7 baar tap (Developer options on) → Developer
  options → **Wireless debugging** ON → *Pair device with pairing code* → jo PORT aur 6-digit CODE
  dikhe wo app ki pairing screen me daal do. Pairing ek baar hoti hai; connect wala port phone
  restart ya toggle par badalta hai.
- **Dusre phone pe.** Same Wi-Fi ka koi bhi Android phone, wahi steps, bas uska address. Uske
  liye **scrcpy** se uski screen bhi yahin dikh sakti hai. Apne hi phone pe scrcpy mat chalana --
  mirror ka mirror ban jaata hai.
- Isse APK install, app auto-open, `adb shell input` se tap/swipe/type, `uiautomator dump`,
  screencap, `pm list packages` aur logcat sab hota hai. Yehi cheez ek AI agent ko bhi MCP se
  milti hai, isliye agent tumhara app khud test kar sakta hai.
- **Cable (USB-OTG) se adb nahi chalega**, aur wo kabhi add nahi hoga: ek normal Android app
  `/dev/bus/usb` padh hi nahi sakta, isliye cable wala adb kisi device ko dhoondh nahi paata.
  Wireless debugging hi raasta hai aur default bhi wahi hona chahiye.
- Wireless debugging on hone ka matlab hai ki computer ke andar ke programs is phone par app
  install kar sakte hain. Kaam khatam ho to use off kar do. Yahi baat app ke safety answer me bhi
  likhi hai.

## 10. Linux kyun, kaunsa Ubuntu, aur Google ke Linux Terminal se farak

**Kaunsa Ubuntu.** Ubuntu **24.04 LTS ARM64** ("noble"). App official `ubuntu-base 24.04.4 arm64`
archive download karta hai aur uska SHA-256 pehle se app ke andar likha hai; hash match na ho to
file use hi nahi hoti. Ubuntu is release ko **April 2029** tak security updates deta hai. Tools →
Software se jo bhi install hota hai wo Ubuntu ke signed ARM64 repository se aata hai: apt pehle
signed `InRelease` file ka signature check karta hai, phir har `.deb` ka SHA-256 usi signed list
se milata hai. Iska sahi matlab hai **"Ubuntu ne sign kiya hai"**, na ki "scan karke safe bata
diya gaya hai". Guarantee yeh hai ki archive asli hai; yeh nahi ki koi ek app achha hai.

**Linux kyun.** Jo bhi bada AI coding tool Linux build deta hai, wo **ARM64 build bhi deta hai**:
Claude Code, Claude Desktop, Cursor, ChatGPT ka desktop app aur Google Chrome -- sabke official
arm64 Linux packages hain. Matlab ye is phone par waise hi install hote hain jaise ek Linux PC
par. (Kaunsa platform "sabse achha" hai, ye is app ka dawa nahi hai. Upar wali baat check ki ja
sakti hai; "sabse achha" check nahi ki ja sakti.)

**Google ka apna Android Linux Terminal.** Wo Debian ko ek **asli virtual machine** me chalata
hai, apne kernel ke saath. Isliye compile aur bhaari file wale kaam me wo PocketLinux se tez hai,
aur Docker jaisi cheezein bhi usme chal sakti hain. Do baatein saath me aati hain:

- Wo sirf un phones pe milta hai jinme **Android Virtualization Framework** ho. Jo chips report
  hui hain: Google Tensor G1 ya usse naya, MediaTek Dimensity 9400 ya usse naya, ya Samsung
  Exynos 2500. Qualcomm Snapdragon phones ise support nahi karte. On bhi Developer options ke
  andar se hota hai.
- **Koi bhi third-party app us virtual machine ko use nahi kar sakta.** AVF ke Java API sab
  `@SystemApi` hain aur unke liye `MANAGE_VIRTUAL_MACHINE` permission chahiye, jo sirf
  preinstalled ya privileged apps ko milti hai. Yeh PocketLinux ki kami nahi hai -- kisi bhi phone
  par koi bhi install kiya hua app ye nahi kar sakta.

Isliye PocketLinux PRoot use karta hai: koi khaas chip nahi, developer mode nahi, root nahi, aur
kisi bhi ARM64 phone par. Iski keemat speed hai. Ek asli PC se yeh saaf-saaf dheema hai, khaas kar
packages install karte waqt aur compile karte waqt. Home ke **Linux only, on purpose** card me
yahi tulna dates ke saath likhi hui hai.

**Isse bana kya sakte ho.** Terminal wala kaam sab: Python, Node.js, Git, gcc/make, SSH se kisi
asli server par kaam. Chrome me poora web. Aur Android app development -- Java 21 aur Gradle ke
saath APK build hota hai, kyunki Google ARM64 Linux ke liye apna aapt2 nahi deta lekin Ubuntu
apna khud banata hai aur app Gradle ko usi par point kar deta hai.

## Privacy — chhoti si baat

- Desktop ki screen **private socket** se aati hai (app ke apne storage ke andar) aur ab kabhi
  kisi network port par nahi: purana `127.0.0.1:5901` wala fallback hata diya gaya hai, aur server
  ko TCP port bind karne se hi mana kar diya gaya hai. Android par loopback har app ko milta hai,
  isliye yeh farak asli hai. Awaaz bhi usi tarah private socket se aati hai; sirf agar wo socket
  ban hi na paye to sound `127.0.0.1:4712` par fallback karti hai, aur app us port ko tabhi try
  karta hai jab desktop khud likh de ki fallback hua.
- App lock ON ho to recents me bhi app ka screenshot nahi dikhega.
- Setup beech me ruk gaya ya computer ka koi hissa gayab ho gaya to app usse **repair** karta hai, delete nahi — delete sirf Settings se, warning ke saath.

## Kya local phone ke andar kaam nahi karega (permanent)

Har line ka matlab hai: **yeh kabhi nahi hoga**, koi update ise nahi badlega. Saath me wo cheez
likhi hai jo sach me milti hai.

- **Windows ya macOS.** Phone par koi normal unrooted app hardware virtual machine bana hi nahi
  sakta, aur macOS sirf Apple ke apne computer par licensed hai. Compatibility layer bhi option
  nahi (upar "Windows apps kyun nahi" dekho). *Jagah:* wahi AI apps ke official Linux ARM64
  build, jo yahin chalte hain.
- **iOS app, iOS simulator ya iOS device farm.** ARM64 Linux ke liye koi legal, chalne wala iOS
  runtime hai hi nahi; Apple apna OS sirf Apple hardware ke liye license karta hai. iOS kabhi add
  nahi hoga. *Jagah:* paisa dekar cloud device farm (jaise BrowserStack App Live) jo asli Apple
  hardware par chalta hai, aur usko desktop ke Chrome se use karo.
- **Android emulator (Android Studio AVD, Cuttlefish, Waydroid, redroid, Anbox).** Har raaste ko
  ek permission chahiye jo PRoot de hi nahi sakta: emulator ko hardware virtualisation
  (`/dev/kvm`) chahiye, aur Android wo kisi app ko nahi deta. *Jagah:* **adb workbench**
  (section 9) -- asli phone hi test device hai, yehi phone ya same Wi-Fi ka dusra phone.
- **Docker, systemd services, snap, Flatpak.** Chaaron ko kernel-level isolation chahiye jo PRoot
  deta hi nahi: yahan PID/network/IPC namespaces, cgroups, mount, modprobe aur FUSE kuch nahi
  hai. Kernel phone ka hai, isliye apne kernel modules aur KVM bhi nahi. *Jagah:* software ke liye
  `apt`, aur service ki jagah ek simple background process.
- **AppImage seedha nahi chalta** -- uska runtime apne aap ko FUSE se mount karta hai. *Jagah:*
  `--appimage-extract-and-run`. Waise bhi ARM64 AppImage bante hi bahut kam hain.
- **Claude Desktop ka Cowork tab.** Cowork ek QEMU virtual machine boot karta hai aur usko
  `/dev/kvm` aur `/dev/vhost-vsock` chahiye, bina kisi software fallback ke; Anthropic ke apne
  docs container wale Linux ko hi failing case bataate hain, aur wo ~25 GB disk aur kam se kam
  8 GB RAM maangta hai. *Jagah:* usi app ka **Chat** aur **Claude Code** -- wo yahan chalte hain.
  App ki apni row me bhi yeh likha hai.
- **Asli glass/blur effects, GPU acceleration, hardware video decode.** Android `/dev/dri` ek
  normal app ko nahi deta, isliye sab kuch processor par draw hota hai; live blur ka kharcha itna
  hai ki wo phone par dheema nahi, toota hua lagta hai. *Jagah:* pehle se blur kiya hua wallpaper
  aur flat opaque panel -- dikhne me premium, kharcha zero.
- **2 GB phone kaafi nahi hai.** App 2 GB par Linux desktop aur terminal khulne deta hai, par AI
  apps nahi -- aur browser bhi wahan theek se nahi chalta: Android khud ~1-1.2 GB leta hai, aur
  desktop ke baad 700 MB se bhi kam bachta hai, jabki Chrome install hone par hi ~428 MB aur
  Firefox ~283 MB leta hai, chalne se pehle. Practical minimum **4 GB** hai. *Jagah:* link phone
  ke apne browser me kholo, aur computer ko terminal wale kaam ke liye rakho.
- **amd64-only software.** Native ARM64 `.deb` use karo; installer amd64 file ko reason ke saath
  rok deta hai, chup-chaap fail nahi hota.
- Microphone: desktop me **More ▾ → Microphone** se on hota hai; har start par off aur desktop
  chhodte hi band.
