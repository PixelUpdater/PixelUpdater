### 2025-07-21 v0.120-release

Merge pull request to address selinux timestamp modification detection.

### 2025-05-09 v0.110-release

- cleaner uninstall - Improved network detection - Use use exponential backoff when retrying connection.

### 2025-03-18 v0.109.1-release

- Fix: #13 Cleanup resources - Update the tag format

### 2025-03-17 v0.109-release

- Fix: #8 Improved detection of successful Magisk patches when exit codes aren't zero. - Fix: #9 Notification icons failing on some devices with fallback icon handling. - Fix: #10 Added Russian translations contributed by @Xusysy. - Added "Preparing to install update..." notification when clicking Install. - Added support file generation tool for troubleshooting. - Added immediate toast feedback when installation constraints aren't met. - Improved update engine status verification before showing notifications. - Enhanced Magisk detection for inactive slots with fallback methods. - Fixed connectivity checking with more robust network detection. - Added Ukranian translations contributed by @Xusysy.

### 2025-03-17 v0.109-release

- Fix: #8 Improved detection of successful Magisk patches when exit codes aren't zero. - Fix: #9 Notification icons failing on some devices with fallback icon handling. - Fix: #10 Added Russian translations contributed by @Xusysy. - Added "Preparing to install update..." notification when clicking Install. - Added support file generation tool for troubleshooting. - Added immediate toast feedback when installation constraints aren't met. - Improved update engine status verification before showing notifications. - Enhanced Magisk detection for inactive slots with fallback methods. - Fixed connectivity checking with more robust network detection.

### 2025-03-17 v0.109-release

- Fix: #8 Improved detection of successful Magisk patches when exit codes aren't zero. - Fix: #9 Notification icons failing on some devices with fallback icon handling. - Fix: #10 Added Russian translations contributed by @Xusysy. - Added "Preparing to install update..." notification when clicking Install. - Added support file generation tool for troubleshooting. - Added immediate toast feedback when installation constraints aren't met. - Improved update engine status verification before showing notifications. - Enhanced Magisk detection for inactive slots with fallback methods. - Fixed connectivity checking with more robust network detection.

### 2025-01-17 v0.108-release

- Clear leftover over data on fresh install and uninstall - Move the `pixelupdater_selinux.log` out of /data/local/tmp/ so that apps don't key on that. - Update verification-metadata.xml for tensorflow-lite-metadata-0.1.0-rc2.pom

### 2024-03-26 v0.107-release

- Update release action. - add uninstall.sh to module build.

### 2024-03-26 v0.105-release

add module uninstall script

### 2023-12-06 v0.104-release

build release v0.104

### 2023-10-18 v0.102-release

- Test dark mode

### 2023-10-14 v0.101-release

- Check for updates on allow reinstall

### 2023-10-14 v0.1-release

- fork as Pixel Updater
- add scraper
- add options for Magisk and `vbmeta` patching
- drop automatic updates
- prep `release` pipeline
- move update json and changelog to separate branch
- add Windows support
