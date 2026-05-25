# MarketingNotificationBlocker

A lightweight, high-performance Android notification filtering module built on the Xposed / LSPosed framework. By hooking deep into the system layer, it accurately intercepts and blocks annoying marketing ads and spam push notifications, restoring a clean and tidy notification shade experience.

## ✨ Core Features

* **System-Level Hooking (Notification Hook)**: Intervenes in the notification generation workflow at the system level, directly blocking spam pushes to prevent useless notifications from waking up the screen or making sounds.
* **Flexible Regex Matching (Regex Configuration)**: Supports highly customizable regular expression rules to precisely match specific keywords (such as ads and marketing promos) while avoiding accidental blocks of important messages.
* **Channel Management**: Deeply takes over the Android system's Notification Channels, allowing you to log and directly freeze specific push channels of rogue applications.
* **Lightweight & Efficient**: Features a highly optimized code structure with minimal runtime overhead, complete with built-in environment validation and safety mechanisms.

## 🛠️ Requirements

To run this module properly, your device must meet the following requirements:

* Unlocked Bootloader with Root access.
* Successfully installed and activated **LSPosed** framework.
* Supported Android Versions: Android 15, Android 16 (Other Android versions are untested but may work; verified on HyperOS 3.0 and crDroid 11.2).

## 📦 Installation & Setup

1. Go to the [Releases](https://github.com/lm060719/io.mo.mnblocker/releases) page and download the latest `.apk` package.
2. Install the APK file normally on your device.
3. Open the **LSPosed Manager**, locate **MarketingNotificationBlocker** in the "Modules" list, and enable it.
4. Select the target scope as needed (typically, "Android System Framework" and the specific rogue applications you want to clean up must be selected).
5. Reboot your device (or restart System UI) to apply the module injection.
6. Open the app interface to start configuring your custom regular expressions and filtering rules.

## ⚠️ Disclaimer

This module is intended solely for educational and research purposes regarding low-level Android Hooking techniques. Please configure your interception rules carefully to avoid missing critical notifications (e.g., instant messages, verification codes). The developer assumes no responsibility for any data or information loss caused by the use of this module.

## 🤝 Contributing & Feedback

If you have optimized regex rules to share or encounter any bugs during daily use, feel free to submit an **Issue** or **Pull Request**! Let's make this project better together.

## 📄 License

This project is licensed under the [MIT License](LICENSE).
