# Open-source notices — NexaDesk Linux

NexaDesk Linux bundles runtime components extracted from official Termux ARM64 packages. PRoot's compiled private-prefix string was changed from the same-length `com.termux` to `com.ndockx`. Its runtime search path was changed to `$ORIGIN`, and the equal-length `libtalloc.so.2` dependency name was changed to `libtallocxx.so`, so all executable components can remain in Android's signed native-library area. These byte-level substitutions are reproducible from the listed Termux package and fingerprints.

| Component | Bundled version | License / source |
|---|---:|---|
| PRoot (Termux build) | 5.1.107.92 | GPL-2.0-or-later; https://github.com/termux/proot and https://github.com/termux/termux-packages/tree/master/packages/proot |
| libandroid-shmem | 0.7 | Apache-2.0; https://github.com/termux/libandroid-shmem |
| talloc runtime | 2.4.3 | LGPL-3.0-or-later; https://talloc.samba.org/ |

Binary package sources and recorded fingerprints:

- `proot_5.1.107.92_aarch64.deb` — SHA-256 `1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9`
- `libandroid-shmem_0.7_aarch64.deb` — SHA-256 `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6`
- `libtalloc_2.4.3_aarch64.deb` — SHA-256 `ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da`

Packages were obtained from `https://packages.termux.dev/apt/termux-main/`. Corresponding source and license texts are available from the linked upstream projects and Termux packaging repository. These components remain under their respective licenses. NexaDesk Linux's Java application sources are provided alongside the APK for inspection and reproducible rebuilding.
